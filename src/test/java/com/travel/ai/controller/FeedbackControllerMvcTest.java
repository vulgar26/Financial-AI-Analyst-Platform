package com.travel.ai.controller;

import com.travel.ai.feedback.FeedbackService;
import com.travel.ai.feedback.dto.FeedbackCreatedResponse;
import com.travel.ai.feedback.dto.FeedbackListResponse;
import com.travel.ai.feedback.dto.FeedbackItemResponse;
import com.travel.ai.feedback.dto.FeedbackSubmitRequest;
import com.travel.ai.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = FeedbackController.class)
@AutoConfigureMockMvc(addFilters = false)
class FeedbackControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FeedbackService feedbackService;

    @MockBean
    private JwtService jwtService;

    @Test
    @WithMockUser(username = "demo")
    void post_creates_201() throws Exception {
        when(feedbackService.submit(any(FeedbackSubmitRequest.class)))
                .thenReturn(new FeedbackCreatedResponse(42L, Instant.parse("2026-04-23T12:00:00Z")));
        mockMvc.perform(post("/travel/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thumb\":\"down\",\"rating\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.created_at").exists());
    }

    @Test
    @WithMockUser(username = "demo")
    void get_lists() throws Exception {
        FeedbackItemResponse it = new FeedbackItemResponse();
        it.setId(1L);
        it.setThumb("up");
        it.setCreatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        when(feedbackService.listMine(anyInt(), anyInt())).thenReturn(new FeedbackListResponse(List.of(it)));
        mockMvc.perform(get("/travel/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].thumb").value("up"));
    }
}
