package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalChatStageTrace;
import com.travel.ai.eval.dto.EvalChatToolTrace;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;

import java.util.Locale;
import java.util.Map;

/**
 * Maps runtime traces to Eval Contract V1 DTOs.
 */
public final class RuntimeEvalTraceMapper {

    private static final String STAGE_KIND = "workflow_node";

    private RuntimeEvalTraceMapper() {
    }

    public static EvalChatStageTrace toEvalStageTrace(StageTrace trace) {
        if (trace == null) {
            return null;
        }
        EvalChatStageTrace dto = new EvalChatStageTrace();
        dto.setStage(trace.stage());
        dto.setKind(STAGE_KIND);
        dto.setStatus(status(trace.status()));
        dto.setElapsedMs(trace.elapsedMs());
        dto.setErrorCode(trace.errorCode());
        if (trace.attrs() != null && !trace.attrs().isEmpty()) {
            dto.setAttrs(trace.attrs());
        }
        return dto;
    }

    public static EvalChatToolTrace toEvalToolTrace(ToolTrace trace) {
        if (trace == null) {
            return null;
        }
        EvalChatToolTrace dto = new EvalChatToolTrace();
        dto.setToolName(trace.toolName());
        dto.setConnector(trace.connector());
        dto.setRequired(trace.required());
        dto.setUsed(trace.used());
        dto.setSucceeded(trace.succeeded());
        dto.setOutcome(trace.outcome());
        dto.setLatencyMs(trace.latencyMs());
        dto.setErrorCode(trace.errorCode());
        dto.setInputRef(trace.inputRef());
        dto.setOutputRef(trace.outputRef());
        if (trace.attrs() != null && !trace.attrs().isEmpty()) {
            dto.setAttrs(trace.attrs());
        }
        return dto;
    }

    private static String status(NodeStatus status) {
        return status != null ? status.name().toLowerCase(Locale.ROOT) : null;
    }
}
