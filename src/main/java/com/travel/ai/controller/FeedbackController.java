package com.travel.ai.controller;

import com.travel.ai.feedback.FeedbackService;
import com.travel.ai.feedback.dto.FeedbackCreatedResponse;
import com.travel.ai.feedback.dto.FeedbackListResponse;
import com.travel.ai.feedback.dto.FeedbackSubmitRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1-3：用户对回答的反馈（点赞/点踩/评分/短评），仅当前 JWT 用户；可选字段与评测 case / tags 对齐便于离线归因。
 */
@RestController
@RequestMapping({"/travel/feedback", "/analysis/feedback", "/finance/feedback"})
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(@RequestBody(required = false) FeedbackSubmitRequest body) {
        if (body == null) {
            return badRequest("MISSING_BODY", "JSON body is required");
        }
        try {
            FeedbackCreatedResponse created = feedbackService.submit(body);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            return badRequest("INVALID_FEEDBACK", ex.getMessage() != null ? ex.getMessage() : "invalid");
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public FeedbackListResponse list(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset
    ) {
        return feedbackService.listMine(limit, offset);
    }

    private static ResponseEntity<Map<String, String>> badRequest(String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("error", code);
        m.put("message", message);
        return ResponseEntity.badRequest().body(m);
    }
}
