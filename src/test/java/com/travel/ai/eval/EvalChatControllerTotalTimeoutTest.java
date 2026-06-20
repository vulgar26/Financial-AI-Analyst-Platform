package com.travel.ai.eval;

import com.travel.ai.agent.QueryRewriter;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.config.AppEvalProperties;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证 {@link EvalChatController} 对 {@link com.travel.ai.eval.EvalChatService#buildStubResponse} 的整段
 * {@code app.agent.total-timeout} 硬中断（与 Servlet 线程隔离）。
 */
@WebMvcTest(controllers = EvalChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(
        properties = {
                "app.agent.total-timeout=300ms",
                "app.eval.stub-work-sleep-ms=900",
                "app.eval.tool-timeout-ms=50"
        }
)
@EnableConfigurationProperties({AppAgentProperties.class, AppEvalProperties.class})
@Import({
        EvalChatService.class,
        PlanParseCoordinator.class,
        PlanParser.class,
        EvalToolStageRunner.class,
        EvalChatControllerTestConfig.class,
        EvalChatTimeoutExecutorConfig.class
})
class EvalChatControllerTotalTimeoutTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private QueryRewriter queryRewriter;

    @BeforeEach
    void defaults() {
        lenient().when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of());
        lenient().when(queryRewriter.rewrite(any())).thenReturn(java.util.List.of("stub-rewrite"));
        lenient().when(queryRewriter.rewriteWithOutcome(any())).thenReturn(
                new com.travel.ai.agent.QueryRewriter.RewriteOutcome(java.util.List.of("stub-rewrite"), false));
    }

    @Test
    void whenStubWorkExceedsTotalTimeout_returnsAgentTotalTimeout() throws Exception {
        String body = """
                {"query":"贵州茅台基本面分析与估值偏好说明加长","mode":"AGENT","conversation_id":"c1"}
                """;
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.behavior").value("clarify"))
                .andExpect(jsonPath("$.error_code").value(EvalChatService.ERROR_CODE_AGENT_TOTAL_TIMEOUT))
                .andExpect(jsonPath("$.meta.stage_order.length()").value(0))
                .andExpect(jsonPath("$.meta.step_count").value(0))
                .andExpect(jsonPath("$.meta.agent_total_timeout_ms").value(300))
                .andExpect(jsonPath("$.meta.agent_latency_budget_exceeded").value(true))
                .andExpect(jsonPath("$.meta.recovery_action").value("aborted"))
                .andExpect(jsonPath("$.latency_ms", lessThanOrEqualTo(700)));
    }
}
