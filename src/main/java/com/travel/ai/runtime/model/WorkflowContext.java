package com.travel.ai.runtime.model;

import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable runtime context owned by one workflow execution.
 *
 * <p>Phase R1 keeps this separate from TravelAgent's MainAgentTurnContext.</p>
 */
public class WorkflowContext {

    private final WorkflowTask task;
    private final Map<String, String> attrs = new LinkedHashMap<>();
    private final List<StageTrace> stageTraces = new ArrayList<>();
    private final List<ToolTrace> toolTraces = new ArrayList<>();
    private final List<PolicyEvent> policyEvents = new ArrayList<>();

    public WorkflowContext(WorkflowTask task) {
        this.task = Objects.requireNonNull(task, "task must not be null");
    }

    public WorkflowTask getTask() {
        return task;
    }

    public Map<String, String> getAttrs() {
        return Collections.unmodifiableMap(attrs);
    }

    public void putAttr(String key, String value) {
        if (key != null && !key.isBlank() && value != null) {
            attrs.put(key, value);
        }
    }

    public void putAttrs(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach(this::putAttr);
    }

    public List<StageTrace> getStageTraces() {
        return Collections.unmodifiableList(stageTraces);
    }

    public void addStageTrace(StageTrace trace) {
        if (trace != null) {
            stageTraces.add(trace);
        }
    }

    public List<ToolTrace> getToolTraces() {
        return Collections.unmodifiableList(toolTraces);
    }

    public void addToolTrace(ToolTrace trace) {
        if (trace != null) {
            toolTraces.add(trace);
        }
    }

    public List<PolicyEvent> getPolicyEvents() {
        return Collections.unmodifiableList(policyEvents);
    }

    public void addPolicyEvent(PolicyEvent event) {
        if (event != null) {
            policyEvents.add(event);
        }
    }
}
