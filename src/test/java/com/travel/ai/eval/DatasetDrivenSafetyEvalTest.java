package com.travel.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.agent.QueryRewriter;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.config.AppEvalProperties;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 3 / B：数据集驱动的安全门控评测（单一事实源）。
 * <p>
 * 把原先散落在 {@link EvalChatControllerTest} 里逐条硬编码的「query 文本可触发」安全/放行用例，
 * 收敛到 classpath 数据集 {@code eval/finance-safety-p0.jsonl}（schema 沿用 eval 项目 day2-sample：
 * {@code case_id/question/expected_behavior/requires_citations/tags}，并加 P0 可选 {@code expected_error_code}）。
 * <p>
 * <b>为什么这层只收「query 文本可触发」的用例</b>：本测试<b>忠实模拟 eval 批跑器</b>（{@code RunRunner} 仅把
 * {@code question} 发给被测接口，不下发 {@code mode}/{@code eval_rag_scenario} 等请求参数）。因此 replan、low_conf
 * 这类靠请求参数触发的场景<b>递不进来</b>，刻意不收（仍由 {@link EvalChatControllerTest} 的契约单测覆盖）；
 * 断言深入 {@code meta} 细粒度字段（{@code low_confidence_reasons[0]}、{@code step_count} 等）的用例也保留在原处，
 * 因为本数据集 schema 只表达 behavior/error_code 级别的粗粒度期望。
 * <p>
 * 单一事实源边界：本仓是该数据集的<b>唯一权威</b>。eval 批跑那份等真正能跑批（VPN/服务起来）时再决定如何接，
 * 现在不提前跨仓复制（避免「同一事实写两遍会脱钩」的新同步债）。
 */
@WebMvcTest(controllers = EvalChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "app.eval.tool-timeout-ms=50",
        "app.eval.llm-real-enabled=false",
        "app.eval.llm-real-timeout-ms=200"
})
@EnableConfigurationProperties({AppAgentProperties.class, AppEvalProperties.class})
@Import({
        EvalChatService.class,
        PlanParseCoordinator.class,
        PlanParser.class,
        EvalToolStageRunner.class,
        EvalChatControllerTestConfig.class,
        EvalChatTimeoutExecutorConfig.class
})
class DatasetDrivenSafetyEvalTest {

    private static final String DATASET = "eval/finance-safety-p0.jsonl";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private QueryRewriter queryRewriter;

    @MockBean(name = "evalUsageChatClient")
    private org.springframework.ai.chat.client.ChatClient evalUsageChatClient;

    @BeforeEach
    void stubCollaborators() {
        // 放行用例需走完整 RAG 流水线；给一条命中 doc，使普通问句不落空命中 clarify、稳定走到 answer。
        lenient().when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("贵州茅台 2023 年毛利率约 91%，净利率约 52%，盈利能力领先白酒板块。")));
        lenient().when(queryRewriter.rewrite(any())).thenReturn(List.of("stub-rewrite"));
        lenient().when(queryRewriter.rewriteWithOutcome(any())).thenReturn(
                new QueryRewriter.RewriteOutcome(List.of("stub-rewrite"), false));
    }

    /**
     * 忠实模拟批跑器：只把 {@code question} 作为 {@code query} 发出，不下发任何评测专用请求参数。
     */
    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("datasetCases")
    void datasetCaseMatchesExpectedBehavior(String caseId, String question,
                                            String expectedBehavior, String expectedErrorCode) throws Exception {
        String body = MAPPER.createObjectNode().put("query", question).toString();
        var req = mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.behavior").value(expectedBehavior));
        if (expectedErrorCode != null && !expectedErrorCode.isBlank()) {
            req.andExpect(jsonPath("$.error_code").value(expectedErrorCode));
        }
    }

    static Stream<Arguments> datasetCases() throws Exception {
        List<Arguments> rows = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(DATASET).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode n = MAPPER.readTree(trimmed);
                rows.add(Arguments.of(
                        n.path("case_id").asText(),
                        n.path("question").asText(),
                        n.path("expected_behavior").asText(),
                        n.path("expected_error_code").asText(null)
                ));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("dataset empty or not found on classpath: " + DATASET);
        }
        return rows.stream();
    }
}
