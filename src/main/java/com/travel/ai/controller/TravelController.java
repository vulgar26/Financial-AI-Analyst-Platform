package com.travel.ai.controller;

import com.travel.ai.agent.TravelAgent;
import com.travel.ai.config.AppConversationProperties;
import com.travel.ai.controller.dto.TravelChatRequest;
import com.travel.ai.conversation.ConversationIdValidator;
import com.travel.ai.conversation.ConversationRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Legacy-compatible SSE entrypoint for the finance analyst workflow.
 * <p>
 * New clients should prefer {@code /analysis/**}; {@code /finance/**} is the finance-semantic alias.
 * {@code /travel/**} remains available for existing clients.
 */
@RestController
@RequestMapping("/travel")
public class TravelController {

    @Autowired
    private TravelAgent travelAgent;

    @Autowired
    private ConversationRegistry conversationRegistry;

    @Autowired
    private AppConversationProperties appConversationProperties;

    /**
     * Legacy-compatible conversation registration for {@code /travel/**}.
     * New clients should call {@code POST /analysis/conversations} or {@code POST /finance/conversations}
     * before opening the matching chat stream when registration is required.
     */
    @PostMapping("/conversations")
    public ResponseEntity<Map<String, String>> createConversation() {
        String id = conversationRegistry.createAndRegister();
        return ResponseEntity.ok(Map.of("conversationId", id));
    }

    /**
     * Legacy-compatible POST chat route. New clients should prefer
     * {@code POST /analysis/chat/{conversationId}} with the same JSON body.
     */
    @PostMapping(value = "/chat/{conversationId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> chatPost(
            HttpServletResponse response,
            @PathVariable String conversationId,
            @RequestBody TravelChatRequest request) {
        assertConversationAllowed(conversationId);
        String query = request != null ? request.getQuery() : null;
        String normalized = validateAndNormalizeQuery(query);
        stampSseHeaders(response);
        return travelAgent.chat(conversationId, normalized);
    }

    /**
     * Deprecated legacy GET route. New clients should use
     * {@code POST /analysis/chat/{conversationId}} with a JSON body.
     */
    @Deprecated
    @GetMapping(value = "/chat/{conversationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<ServerSentEvent<String>> chat(
            HttpServletResponse response,
            @PathVariable String conversationId,
            @RequestParam String query) {
        assertConversationAllowed(conversationId);
        String normalized = validateAndNormalizeQuery(query);
        response.setHeader("Deprecation", "true");
        response.setHeader("Link", "</travel/chat/" + conversationId + ">; rel=\"alternate\"; type=\"application/json\"");
        stampSseHeaders(response);
        return travelAgent.chat(conversationId, normalized);
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
