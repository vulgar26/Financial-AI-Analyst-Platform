package com.travel.ai.controller;

import com.travel.ai.controller.dto.AgentTaskCreateRequest;
import com.travel.ai.controller.dto.AgentTaskResponse;
import com.travel.ai.task.AgentTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/analysis/tasks")
public class AnalysisTaskController {

    private final AgentTaskService agentTaskService;

    public AnalysisTaskController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentTaskResponse create(@RequestBody AgentTaskCreateRequest request) {
        try {
            return AgentTaskResponse.from(agentTaskService.create(request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping(value = "/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentTaskResponse get(@PathVariable UUID taskId) {
        return agentTaskService.findMine(taskId)
                .map(AgentTaskResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
    }

    @PostMapping(value = "/{taskId}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentTaskResponse> cancel(@PathVariable UUID taskId) {
        return agentTaskService.cancelMine(taskId)
                .map(row -> ResponseEntity.ok(AgentTaskResponse.from(row)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found"));
    }
}

