package com.travel.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.ai.conversation.ConversationIdValidator;
import com.travel.ai.profile.UserProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户长期画像 CRUD：仅当前 JWT 主体；删除权见 {@link DeleteMapping}。
 */
@RestController
@RequestMapping({"/travel/profile", "/analysis/profile", "/finance/profile"})
public class UserProfileController {

    private final UserProfileService userProfileService;

    public UserProfileController(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public UserProfileService.UserProfileView get() {
        return userProfileService.getForCurrentUser();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> put(@RequestBody(required = false) JsonNode body) {
        JsonNode profile = extractProfile(body);
        if (profile == null) {
            return badRequest("MISSING_PROFILE", "JSON body with field 'profile' (object) is required");
        }
        try {
            return ResponseEntity.ok(userProfileService.replaceForCurrentUser(profile));
        } catch (IllegalArgumentException ex) {
            return invalidProfile(ex.getMessage());
        }
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> patch(@RequestBody(required = false) JsonNode body) {
        JsonNode profile = extractProfile(body);
        if (profile == null) {
            return badRequest("MISSING_PROFILE", "JSON body with field 'profile' (object) is required");
        }
        try {
            return ResponseEntity.ok(userProfileService.patchForCurrentUser(profile));
        } catch (IllegalArgumentException ex) {
            return invalidProfile(ex.getMessage());
        }
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(
            @RequestParam(name = "clearChatMemory", defaultValue = "false") boolean clearChatMemory,
            @RequestParam(required = false) String conversationId) {
        if (conversationId != null && !conversationId.isBlank() && !ConversationIdValidator.isValid(conversationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid conversationId");
        }
        userProfileService.deleteForCurrentUser(clearChatMemory, conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 从当前用户的 Redis 会话拉取文本，调用无记忆模型抽取画像字段（须 {@code app.memory.auto-extract.enabled=true}）。
     */
    @PostMapping(value = "/extract-suggestion", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> extractSuggestion(@RequestBody(required = false) JsonNode body) {
        String conversationId = body != null && body.hasNonNull("conversationId")
                ? body.get("conversationId").asText()
                : null;
        if (!ConversationIdValidator.isValid(conversationId)) {
            return badRequest("INVALID_CONVERSATION_ID", "conversationId is required and must match /travel/chat rules");
        }
        boolean saveAsPending = body != null && body.path("saveAsPending").asBoolean(false);
        try {
            UserProfileService.ExtractionSuggestionResult r = userProfileService.extractSuggestion(conversationId, saveAsPending);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("suggestedPatch", r.suggestedPatch());
            m.put("mergedPreview", r.mergedPreview());
            m.put("currentProfile", r.currentProfile());
            m.put("pendingSaved", r.pendingSaved());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("disabled")) {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("error", "FEATURE_DISABLED");
                err.put("message", ex.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
            }
            return invalidProfile(ex.getMessage());
        }
    }

    @GetMapping(value = "/pending-extraction", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getPendingExtraction(@RequestParam String conversationId) {
        if (!ConversationIdValidator.isValid(conversationId)) {
            return badRequest("INVALID_CONVERSATION_ID", "invalid conversationId");
        }
        return userProfileService.getPendingExtraction(conversationId)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("createdAtEpochMs", p.createdAtEpochMs());
                    m.put("suggestedPatch", p.suggestedPatch());
                    m.put("mergedPreview", p.mergedPreview());
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "NO_PENDING",
                        "message", "no pending profile extraction for this conversation"
                )));
    }

    @PostMapping(value = "/confirm-extraction", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmExtraction(@RequestBody(required = false) JsonNode body) {
        String conversationId = body != null && body.hasNonNull("conversationId")
                ? body.get("conversationId").asText()
                : null;
        if (!ConversationIdValidator.isValid(conversationId)) {
            return badRequest("INVALID_CONVERSATION_ID", "invalid conversationId");
        }
        try {
            return ResponseEntity.ok(userProfileService.confirmPendingExtraction(conversationId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "NO_PENDING",
                    "message", ex.getMessage() != null ? ex.getMessage() : "no pending extraction"
            ));
        }
    }

    @DeleteMapping("/pending-extraction")
    public ResponseEntity<Void> discardPendingExtraction(@RequestParam String conversationId) {
        if (!ConversationIdValidator.isValid(conversationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid conversationId");
        }
        userProfileService.discardPendingExtraction(conversationId);
        return ResponseEntity.noContent().build();
    }

    private static JsonNode extractProfile(JsonNode body) {
        if (body == null || body.isNull() || !body.isObject()) {
            return null;
        }
        if (!body.has("profile")) {
            return null;
        }
        return body.get("profile");
    }

    private static ResponseEntity<Map<String, String>> badRequest(String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return ResponseEntity.badRequest().body(m);
    }

    private static ResponseEntity<Map<String, String>> invalidProfile(String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", "INVALID_PROFILE");
        m.put("message", message != null ? message : "invalid profile");
        return ResponseEntity.badRequest().body(m);
    }
}
