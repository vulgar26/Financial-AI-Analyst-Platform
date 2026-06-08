package com.travel.ai.integration;

import com.travel.ai.TravelAiApplication;
import com.travel.ai.agent.TravelAgent;
import com.travel.ai.config.AppConversationProperties;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 会话登记与路径校验：{@link TravelAgent} 打桩，避免集成测试依赖外网 LLM。
 */
@SpringBootTest(classes = TravelAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class TravelConversationIntegrationTest {

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
    private TravelAgent travelAgent;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AppConversationProperties appConversationProperties;

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
    void postConversations_withoutToken_returns401() {
        ResponseEntity<String> res = restTemplate.postForEntity("/travel/conversations", HttpEntity.EMPTY, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void postConversations_withToken_returnsConversationId() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Map> res = restTemplate.postForEntity("/travel/conversations", new HttpEntity<>(headers), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKeys("conversationId");
        assertThat(res.getBody().get("conversationId").toString()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void getChat_invalidConversationId_returns400() {
        when(travelAgent.chat(anyString(), anyString())).thenReturn(Flux.empty());
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> res = restTemplate.exchange(
                "/travel/chat/bad@id?query=hi",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getChat_strictMode_unregistered_returns403() {
        when(travelAgent.chat(anyString(), anyString())).thenReturn(Flux.empty());
        boolean prev = appConversationProperties.isRequireRegistration();
        appConversationProperties.setRequireRegistration(true);
        try {
            String token = loginDemo();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<String> res = restTemplate.exchange(
                    "/travel/chat/not-registered-1?query=hi",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        } finally {
            appConversationProperties.setRequireRegistration(prev);
        }
    }

    @Test
    void getChat_strictMode_registered_reachesAgent() {
        when(travelAgent.chat(anyString(), anyString())).thenReturn(
                Flux.just(ServerSentEvent.builder("stub").build()));
        boolean prev = appConversationProperties.isRequireRegistration();
        appConversationProperties.setRequireRegistration(true);
        try {
            String token = loginDemo();
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<Map> post = restTemplate.postForEntity(
                    "/travel/conversations", new HttpEntity<>(headers), Map.class);
            String id = post.getBody().get("conversationId").toString();
            ResponseEntity<String> res = restTemplate.exchange(
                    "/travel/chat/" + id + "?query=hi",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        } finally {
            appConversationProperties.setRequireRegistration(prev);
        }
    }

    @Test
    void postChat_withToken_reachesAgent() {
        when(travelAgent.chat(anyString(), anyString())).thenReturn(
                Flux.just(ServerSentEvent.builder("stub").build()));
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.TEXT_EVENT_STREAM));
        String body = "{\"query\":\"hello from post\"}";
        ResponseEntity<String> res = restTemplate.exchange(
                "/travel/chat/c1",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("stub");
    }

    @Test
    void postChat_queryTooLong_returns400() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String longQuery = "x".repeat(257);
        String body = "{\"query\":\"" + longQuery + "\"}";
        ResponseEntity<String> res = restTemplate.exchange(
                "/travel/chat/c1",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("error").contains("max length");
    }

    @Test
    void getChat_queryTooLong_returns400() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        String longQuery = "y".repeat(257);
        ResponseEntity<String> res = restTemplate.exchange(
                "/travel/chat/c1?query=" + longQuery,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("error").contains("max length");
    }
}
