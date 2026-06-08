package com.travel.ai.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travel.ai.TravelAiApplication;
import com.travel.ai.agent.TravelAgent;
import com.travel.ai.profile.ProfileExtractionService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 画像抽取与待确认落库（{@link ProfileExtractionService} 打桩，避免外网 LLM）。
 */
@SpringBootTest(classes = TravelAiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@TestPropertySource(properties = "app.memory.auto-extract.enabled=true")
class UserProfileExtractionIntegrationTest {

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

    @MockBean
    private ProfileExtractionService profileExtractionService;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @BeforeEach
    void stubExtraction() {
        ObjectNode suggested = objectMapper.createObjectNode();
        suggested.put("fromConversation", "demo_value");
        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("fromConversation", "demo_value");
        when(profileExtractionService.extract(anyString(), anyString(), anyString()))
                .thenReturn(new ProfileExtractionService.ExtractionResult(suggested, merged));
    }

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
    void extract_savePending_confirm_appliesProfile() {
        String token = loginDemo();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        String conv = "conv-extract-01";
        String extractBody = "{\"conversationId\":\"" + conv + "\",\"saveAsPending\":true}";
        ResponseEntity<String> ex = restTemplate.exchange(
                "/travel/profile/extract-suggestion",
                HttpMethod.POST,
                new HttpEntity<>(extractBody, headers),
                String.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ex.getBody()).contains("\"pendingSaved\":true");

        ResponseEntity<String> pending = restTemplate.exchange(
                "/travel/profile/pending-extraction?conversationId=" + conv,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody()).contains("fromConversation");

        String confirmBody = "{\"conversationId\":\"" + conv + "\"}";
        ResponseEntity<String> conf = restTemplate.exchange(
                "/travel/profile/confirm-extraction",
                HttpMethod.POST,
                new HttpEntity<>(confirmBody, headers),
                String.class);
        assertThat(conf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conf.getBody()).contains("fromConversation");

        ResponseEntity<String> get = restTemplate.exchange(
                "/travel/profile",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody()).contains("fromConversation");
    }
}
