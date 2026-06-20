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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code llm_mode=real} 且服务端允许时：默认须 {@code eval_tags} 命中 {@code cost/} 前缀，否则不调用 usage 探针 ChatClient。
 */
@WebMvcTest(controllers = EvalChatController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "app.eval.tool-timeout-ms=50",
        "app.eval.llm-real-enabled=true",
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
class EvalChatLlmRealTagGateMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VectorStore vectorStore;

    @MockBean
    private QueryRewriter queryRewriter;

    @MockBean(name = "evalUsageChatClient")
    private org.springframework.ai.chat.client.ChatClient evalUsageChatClient;

    @BeforeEach
    void stubs() {
        lenient().when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        lenient().when(queryRewriter.rewrite(any())).thenReturn(List.of("stub-rewrite"));
        lenient().when(queryRewriter.rewriteWithOutcome(any())).thenReturn(
                new QueryRewriter.RewriteOutcome(List.of("stub-rewrite"), false));
    }

    @Test
    void llmModeReal_withoutEvalTags_skipsProbe_setsTagGateNoTags() throws Exception {
        String body = """
                {"query":"贵州茅台基本面分析与估值偏好说明","mode":"AGENT","llm_mode":"real"}
                """;
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.token_source").value("estimate"))
                .andExpect(jsonPath("$.meta.provider_usage_available").value(false))
                .andExpect(jsonPath("$.meta.provider_usage_failure_reason").value("tag_gate_no_tags"));
        verifyNoInteractions(evalUsageChatClient);
    }

    @Test
    void llmModeReal_withNonMatchingEvalTags_skipsProbe_setsTagGateNoMatch() throws Exception {
        String body = """
                {"query":"贵州茅台基本面分析与估值偏好说明","mode":"AGENT","llm_mode":"real","eval_tags":["p0/smoke"]}
                """;
        mockMvc.perform(post("/api/v1/eval/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.provider_usage_available").value(false))
                .andExpect(jsonPath("$.meta.provider_usage_failure_reason").value("tag_gate_no_match"));
        verifyNoInteractions(evalUsageChatClient);
    }
}
