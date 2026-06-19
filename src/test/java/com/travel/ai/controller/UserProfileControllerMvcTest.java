package com.travel.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.profile.UserProfileService;
import com.travel.ai.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserProfileControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private JwtService jwtService;

    @Test
    @WithMockUser(username = "demo")
    void get_returnsProfile_onLegacyAndAliases() throws Exception {
        when(userProfileService.getForCurrentUser())
                .thenReturn(new UserProfileService.UserProfileView(1, objectMapper.createObjectNode()));

        for (String path : new String[]{"/analysis/profile", "/finance/profile"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.schemaVersion").value(1))
                    .andExpect(jsonPath("$.profile").isMap());
        }
    }

    @Test
    @WithMockUser(username = "demo")
    void put_replacesProfile_onAnalysisAlias() throws Exception {
        when(userProfileService.replaceForCurrentUser(any()))
                .thenReturn(new UserProfileService.UserProfileView(1, objectMapper.createObjectNode().put("focus", "earnings")));

        mockMvc.perform(put("/analysis/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profile\":{\"focus\":\"earnings\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.focus").value("earnings"));
    }

    @Test
    @WithMockUser(username = "demo")
    void delete_deletesProfile_onFinanceAlias() throws Exception {
        mockMvc.perform(delete("/finance/profile"))
                .andExpect(status().isNoContent());

        verify(userProfileService).deleteForCurrentUser(anyBoolean(), nullable(String.class));
    }
}
