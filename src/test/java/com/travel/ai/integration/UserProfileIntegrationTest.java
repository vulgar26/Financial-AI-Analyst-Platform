package com.travel.ai.integration;

import com.travel.ai.TravelAiApplication;
import com.travel.ai.agent.TravelAgent;
import com.travel.ai.config.RedisChatMemory;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code user_profile} 表与 {@code /travel/profile} HTTP 契约（Testcontainers）。
 */
@SpringBootTest(classes = TravelAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class UserProfileIntegrationTest {

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
    private StringRedisTemplate stringRedisTemplate;

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
    void profile_crud_flow() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<String> get0 = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(get0.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get0.getBody()).contains("\"schemaVersion\"");
        assertThat(get0.getBody()).contains("\"profile\":{}");

        String putBody = "{\"profile\":{\"homeCity\":\"杭州\",\"prefersTrain\":true}}";
        ResponseEntity<String> put = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.PUT,
                new HttpEntity<>(putBody, headers),
                String.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).contains("杭州");
        assertThat(put.getBody()).contains("prefersTrain");

        String patchBody = "{\"profile\":{\"homeCity\":null,\"note\":\"short\"}}";
        ResponseEntity<String> patch = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.PATCH,
                new HttpEntity<>(patchBody, headers),
                String.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch.getBody()).contains("short");
        assertThat(patch.getBody()).doesNotContain("杭州");

        ResponseEntity<Void> del = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> get1 = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(get1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get1.getBody()).contains("\"profile\":{}");
    }

    @Test
    void profile_put_tooManySlots_returns400() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        StringBuilder sb = new StringBuilder("{\"profile\":{");
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"k").append(i).append("\":\"x\"");
        }
        sb.append("}}");
        ResponseEntity<String> res = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.PUT,
                new HttpEntity<>(sb.toString(), headers),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("INVALID_PROFILE");
    }

    @Test
    void profile_withoutAuth_returns401() {
        ResponseEntity<String> res = restTemplate.getForEntity("/travel/profile", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void deleteProfile_withClearChatMemory_deletesRedisKeys() {
        String token = loginDemo();
        String conv = "rediswipe01";
        String key = RedisChatMemory.buildKey("demo", conv);
        stringRedisTemplate.opsForValue().set(key, "[]");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Void> del = restTemplate.exchange(
                "/travel/profile?clearChatMemory=true&conversationId=" + conv,
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(stringRedisTemplate.opsForValue().get(key)).isNull();
    }
}
