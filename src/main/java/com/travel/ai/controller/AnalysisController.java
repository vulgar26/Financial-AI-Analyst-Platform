package com.travel.ai.controller;

import com.travel.ai.agent.FinancialAnalystAgent;
import com.travel.ai.config.AppConversationProperties;
import com.travel.ai.controller.dto.AnalysisChatRequest;
import com.travel.ai.conversation.ConversationIdValidator;
import com.travel.ai.conversation.ConversationRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Financial analysis SSE entrypoint. Exposes {@code /analysis/**} with {@code /finance/**} as a semantic alias.
 */
@RestController
@RequestMapping({"/analysis", "/finance"})
public class AnalysisController {

    private final FinancialAnalystAgent financialAnalystAgent;
    private final ConversationRegistry conversationRegistry;
    private final AppConversationProperties appConversationProperties;

    public AnalysisController(
            FinancialAnalystAgent financialAnalystAgent,
            ConversationRegistry conversationRegistry,
            AppConversationProperties appConversationProperties) {
        this.financialAnalystAgent = financialAnalystAgent;
        this.conversationRegistry = conversationRegistry;
        this.appConversationProperties = appConversationProperties;
    }

    @PostMapping("/conversations")
    public ResponseEntity<Map<String, String>> createConversation() {
        String id = conversationRegistry.createAndRegister();
        return ResponseEntity.ok(Map.of("conversationId", id));
    }

    @PostMapping(value = "/chat/{conversationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatPost(
            HttpServletResponse response,
            @PathVariable String conversationId,
            @RequestBody AnalysisChatRequest request) {
        assertConversationAllowed(conversationId);
        String query = request != null ? request.getQuery() : null;
        String normalized = validateAndNormalizeQuery(query);
        stampSseHeaders(response);
        return financialAnalystAgent.chat(conversationId, normalized);
    }

    @GetMapping(value = "/chat/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> chat(
            HttpServletResponse response,
            @PathVariable String conversationId,
            @RequestParam String query) {
        assertConversationAllowed(conversationId);
        String normalized = validateAndNormalizeQuery(query);
        response.setHeader("Link", "</analysis/chat/" + conversationId + ">; rel=\"alternate\"; type=\"application/json\"");
        stampSseHeaders(response);
        return financialAnalystAgent.chat(conversationId, normalized);
    }

    private void assertConversationAllowed(String conversationId) {
        if (!ConversationIdValidator.isValid(conversationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid conversationId");
        }
        if (appConversationProperties.isRequireRegistration()
                && !conversationRegistry.isRegistered(conversationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "conversation not registered for this user");
        }
    }

    private String validateAndNormalizeQuery(String query) {
        if (query == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        String q = query.trim();
        if (q.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must not be blank");
        }
        int max = appConversationProperties.getMaxQueryChars();
        if (max > 0 && q.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "query exceeds max length (" + max + " characters)");
        }
        return q;
    }

    private static void stampSseHeaders(HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }
}

