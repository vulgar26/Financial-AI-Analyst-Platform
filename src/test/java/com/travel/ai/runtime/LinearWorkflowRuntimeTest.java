package com.travel.ai.runtime;

import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import com.travel.ai.runtime.node.WorkflowNode;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinearWorkflowRuntimeTest {

    private final LinearWorkflowRuntime runtime = new LinearWorkflowRuntime();

    @Test
    void runtime_executesNodesInOrder() {
        List<String> calls = new ArrayList<>();
        WorkflowContext ctx = runtime.run(task(), List.of(
                node("PLAN", calls, NodeResult.success()),
                node("RETRIEVE", calls, NodeResult.success()),
                node("GUARD", calls, NodeResult.success())
        ));

        assertThat(calls).containsExactly("PLAN", "RETRIEVE", "GUARD");
        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE", "GUARD");
        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::status)
                .containsExactly(NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SUCCESS);
    }

    @Test
    void runtime_stopsWhenNodeReturnsContinueFalse() {
        List<String> calls = new ArrayList<>();
        WorkflowContext ctx = runtime.run(task(), List.of(
                node("PLAN", calls, NodeResult.success()),
                node("GUARD", calls, NodeResult.success(Map.of("reason", "blocked")).stopWorkflow()),
                node("WRITE", calls, NodeResult.success())
        ));

        assertThat(calls).containsExactly("PLAN", "GUARD");
        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "GUARD");
        assertThat(ctx.getStageTraces().get(1).attrs()).containsEntry("reason", "blocked");
    }

    @Test
    void runtime_recordsSkippedTrace() {
        WorkflowContext ctx = runtime.run(task(), List.of(
                simpleNode("TOOL", NodeResult.skipped("skipped_by_plan", Map.of("reason", "skipped_by_plan")))
        ));

        assertThat(ctx.getStageTraces()).hasSize(1);
        StageTrace trace = ctx.getStageTraces().get(0);
        assertThat(trace.stage()).isEqualTo("TOOL");
        assertThat(trace.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(trace.message()).isEqualTo("skipped_by_plan");
        assertThat(trace.attrs()).containsEntry("reason", "skipped_by_plan");
        assertThat(trace.elapsedMs()).isNotNull();
    }

    @Test
    void runtime_convertsExceptionToFailedTrace() {
        WorkflowContext ctx = runtime.run(task(), List.of(
                simpleNode("PLAN", NodeResult.success()),
                new WorkflowNode() {
                    @Override
                    public String name() {
                        return "RETRIEVE";
                    }

                    @Override
                    public NodeResult execute(WorkflowContext ctx) {
                        throw new IllegalStateException("vector store unavailable");
                    }
                },
                simpleNode("TOOL", NodeResult.success())
        ));

        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE");
        StageTrace failed = ctx.getStageTraces().get(1);
        assertThat(failed.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(NodeResult.ERROR_CODE_NODE_EXCEPTION);
        assertThat(failed.message()).isEqualTo("vector store unavailable");
        assertThat(failed.attrs()).containsEntry("exception", IllegalStateException.class.getName());
    }

    @Test
    void runtime_preservesTraceOrder() {
        WorkflowContext ctx = runtime.run(task(), List.of(
                simpleNode("PLAN", NodeResult.success()),
                simpleNode("RETRIEVE", NodeResult.success()),
                simpleNode("TOOL", NodeResult.skipped("no_tool", Map.of("reason", "not_required"))),
                simpleNode("GUARD", NodeResult.success())
        ));

        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE", "TOOL", "GUARD");
        assertThat(ctx.getStageTraces())
                .extracting(StageTrace::status)
                .containsExactly(NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SKIPPED, NodeStatus.SUCCESS);
    }

    @Test
    void runtime_supportsTaskWorkflowMetadata() {
        WorkflowTask task = new WorkflowTask(
                "market_data_explain",
                "v1",
                "finance",
                "req-123",
                "conv-456",
                "Explain AAPL market data",
                Map.of("tenant", "demo")
        );

        WorkflowContext ctx = runtime.run(task, List.of(
                new WorkflowNode() {
                    @Override
                    public String name() {
                        return "PLAN";
                    }

                    @Override
                    public NodeResult execute(WorkflowContext ctx) {
                        ctx.putAttr("workflow_id", ctx.getTask().workflowId());
                        return NodeResult.success(Map.of("request_id", ctx.getTask().requestId()));
                    }
                }
        ));

        assertThat(ctx.getTask().workflowId()).isEqualTo("market_data_explain");
        assertThat(ctx.getTask().workflowVersion()).isEqualTo("v1");
        assertThat(ctx.getTask().workflowFamily()).isEqualTo("finance");
        assertThat(ctx.getTask().requestId()).isEqualTo("req-123");
        assertThat(ctx.getTask().conversationId()).isEqualTo("conv-456");
        assertThat(ctx.getTask().userInput()).isEqualTo("Explain AAPL market data");
        assertThat(ctx.getTask().attrs()).containsEntry("tenant", "demo");
        assertThat(ctx.getAttrs()).containsEntry("workflow_id", "market_data_explain");
        assertThat(ctx.getStageTraces().get(0).attrs()).containsEntry("request_id", "req-123");
    }

    @Test
    void runtime_redirectsBackToEarlierNodeOnce() {
        List<String> calls = new ArrayList<>();
        // GUARD redirects back to RETRIEVE on its first pass, then proceeds on the second pass.
        WorkflowNode guard = new WorkflowNode() {
            int passes = 0;

            @Override
            public String name() {
                return "GUARD";
            }

            @Override
            public NodeResult execute(WorkflowContext ctx) {
                calls.add("GUARD");
                passes++;
                if (passes == 1) {
                    return NodeResult.redirectTo("RETRIEVE", Map.of("replan", "true"));
                }
                return NodeResult.success();
            }
        };

        WorkflowContext ctx = runtime.run(task(), List.of(
                node("PLAN", calls, NodeResult.success()),
                node("RETRIEVE", calls, NodeResult.success()),
                guard
        ));

        // RETRIEVE runs twice (initial + replan), GUARD runs twice, PLAN once.
        assertThat(calls).containsExactly("PLAN", "RETRIEVE", "GUARD", "RETRIEVE", "GUARD");
        assertThat(ctx.getAttrs()).doesNotContainKey("redirect_capped");
    }

    @Test
    void runtime_capsInfiniteRedirectLoop() {
        List<String> calls = new ArrayList<>();
        // A GUARD that always redirects would loop forever without the engine cap.
        WorkflowNode alwaysRedirect = new WorkflowNode() {
            @Override
            public String name() {
                return "GUARD";
            }

            @Override
            public NodeResult execute(WorkflowContext ctx) {
                calls.add("GUARD");
                return NodeResult.redirectTo("RETRIEVE", Map.of());
            }
        };

        LinearWorkflowRuntime cappedRuntime = new LinearWorkflowRuntime(2);
        WorkflowContext ctx = cappedRuntime.run(task(), List.of(
                node("RETRIEVE", calls, NodeResult.success()),
                alwaysRedirect
        ));

        // 2 redirects allowed: GUARD runs 3 times (initial + 2 replans), then cap forces advance.
        assertThat(calls).containsExactly("RETRIEVE", "GUARD", "RETRIEVE", "GUARD", "RETRIEVE", "GUARD");
        assertThat(ctx.getAttrs()).containsEntry("redirect_capped", "true");
    }

    @Test
    void runtime_advancesWhenRedirectTargetUnknown() {
        List<String> calls = new ArrayList<>();
        WorkflowNode redirectToMissing = new WorkflowNode() {
            @Override
            public String name() {
                return "GUARD";
            }

            @Override
            public NodeResult execute(WorkflowContext ctx) {
                calls.add("GUARD");
                return NodeResult.redirectTo("NONEXISTENT", Map.of());
            }
        };

        WorkflowContext ctx = runtime.run(task(), List.of(
                node("RETRIEVE", calls, NodeResult.success()),
                redirectToMissing
        ));

        // Unknown target cannot be honored: GUARD runs once, workflow terminates without looping.
        assertThat(calls).containsExactly("RETRIEVE", "GUARD");
        assertThat(ctx.getAttrs()).containsEntry("redirect_unresolved", "NONEXISTENT");
    }

    private static WorkflowTask task() {
        return WorkflowTask.of(
                "test_workflow",
                "v1",
                "finance",
                "req-test",
                "conv-test",
                "test input"
        );
    }

    private static WorkflowNode node(String name, List<String> calls, NodeResult result) {
        return new WorkflowNode() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public NodeResult execute(WorkflowContext ctx) {
                calls.add(name);
                return result;
            }
        };
    }

    private static WorkflowNode simpleNode(String name, NodeResult result) {
        return new WorkflowNode() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public NodeResult execute(WorkflowContext ctx) {
                return result;
            }
        };
    }
}
