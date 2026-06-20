package com.travel.ai.integration;

import com.travel.ai.FinanceAgentApplication;
import com.travel.ai.agent.FinancialAnalystAgent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code user_feedback} 表与 {@code /analysis/feedback} HTTP 契约（P1-3 · Testcontainers）。
 */
@SpringBootTest(classes = FinanceAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class FeedbackIntegrationTest {

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

    @MockBean
    private FinancialAnalystAgent agent;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private String loginDemo() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"username\":\"demo\",\"password\":\"demo123\"}";
        ResponseEntity<String> res = restTemplate.postForEntity("/auth/login", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Matcher m = TOKEN.matcher(res.getBody() == null ? "" : res.getBody());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void feedback_post_then_list() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        String postBody = """
                {"thumb":"up","rating":4,"comment":" integration ok ","eval_case_id":"case-it-1","eval_tags":["rag/empty","cost/probe"],"request_id":"req-it-1"}
                """;
        ResponseEntity<String> post = restTemplate.exchange(
                "/analysis/feedback",
                HttpMethod.POST,
                new HttpEntity<>(postBody, headers),
                String.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(post.getBody()).isNotNull().contains("\"id\"").contains("\"created_at\"");

        ResponseEntity<String> list = restTemplate.exchange(
                "/analysis/feedback?limit=10&offset=0",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).contains("\"thumb\":\"up\"").contains("\"rating\":4").contains("integration ok");

        jdbcTemplate.update("DELETE FROM user_feedback WHERE user_id = ?", "demo");
    }

    @Test
    void feedback_post_invalid_thumb_returns_400() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        String postBody = "{\"thumb\":\"sideways\"}";
        ResponseEntity<String> post = restTemplate.exchange(
                "/analysis/feedback",
                HttpMethod.POST,
                new HttpEntity<>(postBody, headers),
                String.class);
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post.getBody()).contains("INVALID_FEEDBACK");
    }
}
