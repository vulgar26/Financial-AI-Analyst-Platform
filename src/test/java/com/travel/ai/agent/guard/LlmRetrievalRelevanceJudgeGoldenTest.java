package com.travel.ai.agent.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第二刀「裁判器本身」的金标准评测（两层 eval 的<strong>语义质量层</strong>）。
 *
 * <p>这是「我怎么知道 LLM 裁判本身判得对」的正面回答：用一批<em>已知答案</em>的样本
 * （N 条明显相关 + N 条明显不相关），喂给真实 LLM 裁判，断言它在金标准上判对。</p>
 *
 * <p><strong>为什么不进 CI 硬门禁</strong>：裁判是真 LLM，结果会抖动、需联网、消耗 token，
 * 把它当红绿门禁会让 CI 既慢又 flaky。故用 {@link EnabledIfEnvironmentVariable} 仅在本机配置了
 * {@code ANTHROPIC_AUTH_TOKEN} 时运行；CI 无 key → 自动跳过，既不失败也不计费。
 * 这正是两层 eval「确定性契约进门禁、语义质量离线手动」分工在裁判器上的落地。</p>
 *
 * <p>断言用「金标准通过率阈值」而非「每条都对」，容忍 LLM 的少量抖动——
 * 一两条边缘样本判错不该让整批失败，但整体准确率必须达标。</p>
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_AUTH_TOKEN", matches = ".+")
class LlmRetrievalRelevanceJudgeGoldenTest {

    /** 金标准通过率下限：允许个别边缘样本抖动，但整体必须达标。 */
    private static final double MIN_ACCURACY = 0.8;

    private static RetrievalRelevanceJudge realJudge() {
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl(System.getenv("ANTHROPIC_BASE_URL"))
                .apiKey(System.getenv("ANTHROPIC_AUTH_TOKEN"))
                .build();
        String model = System.getenv().getOrDefault("ANTHROPIC_CHAT_MODEL", "claude-sonnet-4-5-20250929");
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder().model(model).build())
                .build();
        return new LlmRetrievalRelevanceJudge(ChatClient.builder(chatModel));
    }

    private static Document doc(String text) {
        return new Document(text, Map.of("source_name", "golden"));
    }

    /** 一条金标准样本：问题 + 检索片段 + 期望是否相关。 */
    private record GoldenCase(String name, String question, List<Document> docs, boolean expectedRelevant) {
    }

    private static List<GoldenCase> goldenCases() {
        return List.of(
                // ---- 明显相关 ----
                new GoldenCase("relevant_maotai_margin",
                        "贵州茅台2024年三季度毛利率是多少",
                        List.of(doc("贵州茅台2024年第三季度财报显示，公司毛利率为91.5%，同比微升，主要受益于直销渠道占比提升。")),
                        true),
                new GoldenCase("relevant_apple_revenue",
                        "苹果公司最近一个财季的营收增长情况",
                        List.of(doc("Apple reported quarterly revenue of $94.9 billion, up 6% year over year, driven by services and iPhone sales.")),
                        true),
                new GoldenCase("relevant_byd_risk",
                        "比亚迪面临的主要经营风险有哪些",
                        List.of(doc("比亚迪年报风险提示：原材料价格波动、海外市场政策不确定性、新能源补贴退坡对盈利的潜在影响。")),
                        true),
                // ---- 明显不相关（同领域但答非所问 = RAG 暗坑）----
                new GoldenCase("not_relevant_maotai_tourism",
                        "贵州茅台2024年三季度毛利率是多少",
                        List.of(doc("茅台镇位于贵州省遵义市，是著名的白酒产地和旅游目的地，每年吸引大量游客参观酒厂和体验酿酒文化。")),
                        false),
                new GoldenCase("not_relevant_apple_fruit",
                        "苹果公司最近一个财季的营收增长情况",
                        List.of(doc("苹果是一种富含维生素的水果，建议每天食用一个有助于健康，常见品种有红富士、嘎啦等。")),
                        false),
                new GoldenCase("not_relevant_byd_history",
                        "比亚迪面临的主要经营风险有哪些",
                        List.of(doc("比亚迪成立于1995年，最初以电池业务起家，公司名称取自Build Your Dreams的缩写。")),
                        false)
        );
    }

    @Test
    void judgeMatchesGoldenLabels_aboveAccuracyThreshold() {
        RetrievalRelevanceJudge judge = realJudge();
        List<GoldenCase> cases = goldenCases();

        int correct = 0;
        StringBuilder misses = new StringBuilder();
        for (GoldenCase c : cases) {
            RetrievalRelevanceJudge.Verdict v = judge.judge(c.question(), c.docs());
            // 金标准里裁判必须真正给出判断（available=true）；不可用说明环境/调用有问题。
            assertThat(v.available())
                    .as("golden case '%s' expects a real verdict (judge available)", c.name())
                    .isTrue();
            if (v.relevant() == c.expectedRelevant()) {
                correct++;
            } else {
                misses.append("\n  - ").append(c.name())
                        .append(" expected relevant=").append(c.expectedRelevant())
                        .append(" but got relevant=").append(v.relevant())
                        .append(" reason='").append(v.reason()).append('\'');
            }
        }

        double accuracy = (double) correct / cases.size();
        assertThat(accuracy)
                .as("relevance judge golden accuracy %d/%d; misses:%s", correct, cases.size(), misses)
                .isGreaterThanOrEqualTo(MIN_ACCURACY);
    }
}
