package com.travel.ai.agent.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link RetrievalRelevanceJudge} 的真实 LLM 实现：用一次 chat 调用判断检索片段与问题是否相关。
 *
 * <p>遵循 {@link com.travel.ai.agent.QueryRewriter} 立下的样板：{@code ChatClient.Builder} 注入、
 * {@code defaultSystem(PROMPT)}、{@code .prompt(x).call().content()}，且<strong>任何异常都回退为
 * fail-safe 放行</strong>（见接口契约），绝不抛出、绝不因自身失败触发 replan。</p>
 *
 * <p>输出协议刻意极简：模型只回一行 {@code RELEVANT} 或 {@code NOT_RELEVANT} + 可选理由，
 * 不要求它输出 JSON（少一层解析失败面）、更不让它碰答案本身。</p>
 */
@Component
public final class LlmRetrievalRelevanceJudge implements RetrievalRelevanceJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmRetrievalRelevanceJudge.class);

    /** 喂给裁判的片段最多取前 N 条、每条最多 M 字符，控制 prompt 体积与成本。 */
    private static final int MAX_DOCS = 5;
    private static final int MAX_DOC_CHARS = 500;

    private static final String JUDGE_PROMPT = """
            你是一个检索质量裁判。给你一个用户问题和若干条「检索到的资料片段」，
            你只需判断：这些片段作为证据，能否支撑回答这个问题。

            判断标准：
            1. 片段主题与问题主体/意图一致，且包含可引用的相关事实 → 相关
            2. 片段虽与问题领域沾边但答非所问、主体不符、只有噪音 → 不相关

            严格要求：
            - 只输出第一行结论，且必须是 RELEVANT 或 NOT_RELEVANT 之一
            - 可在第二行给一句简短中文理由
            - 绝对不要尝试回答用户的问题本身，你只做相关性判断
            """;

    private final ChatClient chatClient;

    public LlmRetrievalRelevanceJudge(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem(JUDGE_PROMPT)
                .build();
    }

    @Override
    public Verdict judge(String userQuestion, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            // 零命中是第一刀的领域，本裁判不该被调到这；保守起见放行。
            return Verdict.passThrough();
        }
        String question = userQuestion == null ? "" : userQuestion.trim();
        if (question.isEmpty()) {
            return Verdict.passThrough();
        }

        String snippets = docs.stream()
                .limit(MAX_DOCS)
                .map(d -> {
                    String text = d.getText() == null ? "" : d.getText();
                    return text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text;
                })
                .collect(Collectors.joining("\n---\n"));

        String userPrompt = "【用户问题】\n" + question + "\n\n【检索到的资料片段】\n" + snippets;

        String result;
        try {
            result = chatClient.prompt(userPrompt).call().content();
        } catch (Exception e) {
            log.warn("[relevance-judge] LLM 调用失败，fail-safe 放行。原因={}", e.toString());
            return Verdict.passThrough();
        }

        if (result == null || result.isBlank()) {
            log.warn("[relevance-judge] 模型返回空内容，fail-safe 放行");
            return Verdict.passThrough();
        }

        String head = result.strip().split("\n", 2)[0].trim().toUpperCase();
        if (head.startsWith("NOT_RELEVANT")) {
            log.info("[relevance-judge] verdict=NOT_RELEVANT");
            return Verdict.notRelevant(firstReason(result));
        }
        if (head.startsWith("RELEVANT")) {
            return Verdict.relevant(firstReason(result));
        }
        // 模型没按协议输出：不可信判定，fail-safe 放行（不冒险触发 replan）。
        log.warn("[relevance-judge] 模型输出不符协议 head='{}'，fail-safe 放行", head);
        return Verdict.passThrough();
    }

    private static String firstReason(String result) {
        String[] lines = result.strip().split("\n", 2);
        return lines.length > 1 ? lines[1].trim() : "";
    }
}
