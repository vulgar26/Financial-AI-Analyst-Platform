package com.travel.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travel.ai.TravelAiApplication;
import com.travel.ai.plan.PlanParseCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Week 3 Day 4：用 Testcontainers 拉起 Postgres（pgvector 镜像）与 Redis，
 * 验证 Spring 上下文、Flyway 迁移、Redis、匿名 health 探活，以及登录与鉴权边界（升级 P4-2）。
 * <p>
 * 需本机 Docker 可用；无 Docker 时本类会失败（符合集成测试预期）。
 */
@SpringBootTest(classes = TravelAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class TravelAiApplicationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(IntegrationTestImages.postgresPgvector())
            .withDatabaseName("finance")
            .withUsername("postgres")
            .withPassword("postgres");

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>(IntegrationTestImages.redis())
            .withExposedPorts(6379);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 用 mock 顶替真实 DashScope EmbeddingModel：集成测试只有占位 api-key，
     * 真实 embed() 会 401（既阻断上下文启动，也让 RETRIEVE 物理阶段 500）。
     * 返回固定 1024 维向量（与 V1__init_pgvector.sql 的 vector(1024) 对齐）。
     */
    @MockBean
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void stubEmbedding() {
        float[] vec = new float[1024];
        vec[0] = 1.0f;
        Mockito.when(embeddingModel.embed(Mockito.anyString())).thenReturn(vec);
    }

    @Test
    void contextLoads() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(redis.isRunning()).isTrue();
    }

    @Test
    void flywayAppliedVectorStore() {
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(migrations).isNotNull().isGreaterThanOrEqualTo(1);

        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'vector_store'",
                Integer.class);
        assertThat(tables).isEqualTo(1);

        Integer ckpt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'eval_conversation_checkpoint'",
                Integer.class);
        assertThat(ckpt).isEqualTo(1);
    }

    @Test
    void redisReadWrite() {
        stringRedisTemplate.opsForValue().set("tc:ping", "ok");
        assertThat(stringRedisTemplate.opsForValue().get("tc:ping")).isEqualTo("ok");
    }

    @Test
    void actuatorHealthAnonymousUp() {
        ResponseEntity<String> res = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("UP");
    }

    /**
     * 演示账号登录应返回 JWT；不依赖外网 DashScope，仅测 AuthController + Security 链。
     */
    @Test
    void loginWithDemoCredentialsReturnsToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"demo\",\"password\":\"demo123\"}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/auth/login", entity, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("token");
    }

    /**
     * 收紧 anyRequest().authenticated() 后：未带 Token 访问受保护业务接口应 401。
     */
    @Test
    void travelChatWithoutTokenReturns401() {
        ResponseEntity<String> res = restTemplate.getForEntity("/travel/chat/c1?query=hi", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().toString()).contains("application/json");
        assertThat(res.getBody()).isNotNull().contains("\"error\"").contains("UNAUTHORIZED");
    }

    @Test
    void evalChatWithoutGatewayKeyReturns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"query\":\"ping\"}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/eval/chat", entity, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).isNotNull().contains("EVAL_GATEWAY");
    }

    @Test
    void evalChatWithGatewayKeyReturns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        String body = "{\"query\":\"ping\"}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/eval/chat", entity, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("\"behavior\"");
    }

    /**
     * 检索链路抛未预期异常时，/api/v1/eval/chat 应降级为带 error_code 的 200，而非 500。
     * 通过让 embedding mock 在本用例抛异常来强制 RETRIEVE 阶段失败。
     */
    @Test
    void evalChatRetrievalFailureDegradesTo200WithErrorCode() {
        Mockito.when(embeddingModel.embed(Mockito.anyString()))
                .thenThrow(new RuntimeException("simulated embedding failure"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        String body = "{\"query\":\"ping\",\"mode\":\"EVAL\"}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/eval/chat", entity, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull().contains("AGENT_INTERNAL_ERROR");
    }

    @Test
    void evalChatUpsertsCheckpointWhenConversationIdProvided() {
        String conv = "it-ckpt-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        String q = "it-eval-ckpt-query-" + UUID.randomUUID();
        String body = "{\"query\":\"" + q + "\",\"mode\":\"EVAL\",\"conversation_id\":\"" + conv + "\"}";
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/eval/chat", entity, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        String lastStage = jdbcTemplate.queryForObject(
                "SELECT last_completed_stage FROM eval_conversation_checkpoint WHERE conversation_id = ?",
                String.class,
                conv);
        assertThat(lastStage).isNotBlank();
        String querySha = jdbcTemplate.queryForObject(
                "SELECT detail->>'query_sha256' FROM eval_conversation_checkpoint WHERE conversation_id = ?",
                String.class,
                conv);
        assertThat(querySha).isNotBlank().hasSize(64);
        jdbcTemplate.update("DELETE FROM eval_conversation_checkpoint WHERE conversation_id = ?", conv);
    }

    @Test
    void evalCheckpointPlanMismatchReturnsErrorCodeInBody() throws Exception {
        String conv = "it-ckpt-mis-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        restTemplate.postForEntity(
                "/api/v1/eval/chat",
                new HttpEntity<>("{\"query\":\"a\",\"mode\":\"EVAL\",\"conversation_id\":\"" + conv + "\"}", headers),
                String.class);
        String altPlan = PlanParseCoordinator.DEFAULT_EVAL_PLAN_JSON.replace(
                "评测默认全阶段占位", "评测默认全阶段占位_MISMATCH");
        String planRawField = objectMapper.writeValueAsString(altPlan);
        String body2 = "{\"query\":\"b\",\"mode\":\"EVAL\",\"conversation_id\":\"" + conv + "\",\"plan_raw\":" + planRawField + "}";
        ResponseEntity<String> res2 = restTemplate.postForEntity(
                "/api/v1/eval/chat", new HttpEntity<>(body2, headers), String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody()).isNotNull()
                .contains("EVAL_CHECKPOINT_PLAN_MISMATCH")
                .contains("plan_mismatch");
        jdbcTemplate.update("DELETE FROM eval_conversation_checkpoint WHERE conversation_id = ?", conv);
    }

    @Test
    void evalCheckpointResumeExhaustedOnSecondFullPipelineRequest() throws Exception {
        String planNoRetrieve = """
                {
                  "plan_version": "v1",
                  "goal": "integration checkpoint without retrieve",
                  "steps": [
                    { "step_id": "s1", "stage": "TOOL", "instruction": "tool." },
                    { "step_id": "s2", "stage": "GUARD", "instruction": "guard." },
                    { "step_id": "s3", "stage": "WRITE", "instruction": "write." }
                  ],
                  "constraints": { "max_steps": 8, "total_timeout_ms": 60000, "tool_timeout_ms": 10000 }
                }
                """;
        String conv = "it-ckpt-ex-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        String q = "eval-cp-resume-exhausted-integration-query-extra";
        ObjectNode body1 = objectMapper.createObjectNode();
        body1.put("query", q);
        body1.put("mode", "EVAL");
        body1.put("conversation_id", conv);
        body1.put("plan_raw", planNoRetrieve);
        restTemplate.postForEntity(
                "/api/v1/eval/chat",
                new HttpEntity<>(objectMapper.writeValueAsString(body1), headers),
                String.class);
        ObjectNode body2 = objectMapper.createObjectNode();
        body2.put("query", q);
        body2.put("mode", "EVAL");
        body2.put("conversation_id", conv);
        body2.put("plan_raw", planNoRetrieve);
        ResponseEntity<String> res2 = restTemplate.postForEntity(
                "/api/v1/eval/chat",
                new HttpEntity<>(objectMapper.writeValueAsString(body2), headers),
                String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody()).isNotNull()
                .contains("EVAL_CHECKPOINT_RESUMED_EXHAUSTED")
                .contains("resume_exhausted");
        jdbcTemplate.update("DELETE FROM eval_conversation_checkpoint WHERE conversation_id = ?", conv);
    }

    @Test
    void evalCheckpointPersistsToolSnapshotWhenEvalToolScenarioProvided() throws Exception {
        String planNoRetrieve = """
                {
                  "plan_version": "v1",
                  "goal": "integration checkpoint tool snapshot",
                  "steps": [
                    { "step_id": "s1", "stage": "TOOL", "instruction": "tool." },
                    { "step_id": "s2", "stage": "GUARD", "instruction": "guard." },
                    { "step_id": "s3", "stage": "WRITE", "instruction": "write." }
                  ],
                  "constraints": { "max_steps": 8, "total_timeout_ms": 60000, "tool_timeout_ms": 10000 }
                }
                """;
        String conv = "it-ckpt-tool-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        String body = "{\"query\":\"tool-snapshot-integration-query-extra\",\"mode\":\"EVAL\",\"conversation_id\":\""
                + conv + "\",\"plan_raw\":" + objectMapper.writeValueAsString(planNoRetrieve)
                + ",\"eval_tool_scenario\":\"success\"}";
        restTemplate.postForEntity("/api/v1/eval/chat", new HttpEntity<>(body, headers), String.class);

        String toolOutcome = jdbcTemplate.queryForObject(
                "SELECT detail->>'tool_outcome' FROM eval_conversation_checkpoint WHERE conversation_id = ?",
                String.class,
                conv);
        assertThat(toolOutcome).isNotBlank();
        String toolScenario = jdbcTemplate.queryForObject(
                "SELECT detail->>'eval_tool_scenario' FROM eval_conversation_checkpoint WHERE conversation_id = ?",
                String.class,
                conv);
        assertThat(toolScenario).isEqualTo("success");
        jdbcTemplate.update("DELETE FROM eval_conversation_checkpoint WHERE conversation_id = ?", conv);
    }

    /**
     * 第一次：RAG stub 在 RETRIEVE 后短路并写入断点（含空证据列表）；第二次：同会话同 query 应续跑并跳过重复检索门控，进入 TOOL 及之后阶段。
     */
    @Test
    void evalCheckpointResumeAfterRagEmptyStubWithSameConversation() throws Exception {
        String conv = "it-ckpt-resume-rag-" + UUID.randomUUID();
        String q = "eval-resume-after-rag-empty-" + UUID.randomUUID();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Eval-Gateway-Key", "it-eval-gateway-key");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("query", q);
        body.put("mode", "EVAL");
        body.put("conversation_id", conv);
        body.put("eval_rag_scenario", "empty");

        ResponseEntity<String> res1 = restTemplate.postForEntity(
                "/api/v1/eval/chat",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);
        assertThat(res1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res1.getBody()).isNotNull().contains("RETRIEVE_EMPTY");

        ResponseEntity<String> res2 = restTemplate.postForEntity(
                "/api/v1/eval/chat",
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);
        assertThat(res2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res2.getBody()).isNotNull()
                .contains("resumed")
                .contains("\"checkpoint_evidence_reused\":true")
                .contains("TOOL")
                .doesNotContain("EVAL_CHECKPOINT_RESUMED_EXHAUSTED");

        jdbcTemplate.update("DELETE FROM eval_conversation_checkpoint WHERE conversation_id = ?", conv);
    }
}
