# Workflow Runtime Progress

This document records the current implementation progress of the Finance Agent workflow runtime and its alignment with Eval Contract V1.

The purpose is to keep the architecture boundary explicit:

- Mainline `/analysis/chat` runtime trace and eval `/api/v1/eval/chat` trace are related, but not the same execution path.
- Eval trace is currently synthetic.
- Mainline trace is currently internal runtime state.
- Eval endpoint does not call `TravelAgent`.
- `MainSseChatAdapter` remains a future integration path.

## R1: Runtime Skeleton

R1 introduced the minimal runtime model and execution skeleton without connecting it to the existing agent business path.

Implemented runtime classes:

- `LinearWorkflowRuntime`
- `WorkflowTask`
- `WorkflowContext`
- `WorkflowNode`
- `NodeResult`
- `NodeStatus`
- `StageTrace`
- `ToolTrace`

R1 responsibilities:

- Execute a list of `WorkflowNode` instances in order.
- Record stage execution as `StageTrace`.
- Stop when a node returns `continueWorkflow=false`.
- Convert node exceptions into failed runtime results.
- Keep runtime state separate from `MainAgentTurnContext`.

R1 deliberately did not change:

- `TravelAgent`
- Controller routing
- SSE
- Redis memory
- Eval response contract
- DB or migration

## R2: Runtime Wraps PLAN / RETRIEVE / TOOL / GUARD

R2 connected `LinearWorkflowRuntime` to the mainline path behind a feature flag:

```properties
app.agent.workflow-runtime.enabled=false
```

When the flag is disabled, the old `runLinearStages(ctx)` path remains the default.

When the flag is enabled, `TravelAgent` uses a thin runtime wrapper around the existing stage methods:

- `stagePlan(ctx)`
- `stageRetrieve(ctx)`
- `stageTool(ctx)`
- `stageGuard(ctx)`

R2 does not move business logic out of `TravelAgent`. The runtime only owns stage scheduling and trace collection.

`WRITE` remains outside runtime because it owns streaming behavior:

- `stageWrite(...)`
- `ChatClient.stream()`
- SSE event assembly
- heartbeat
- done/error handling
- Redis memory write coordination

## R2 Acceptance: Flag True / False Behavior

R2 acceptance focused on proving that enabling runtime scheduling does not change externally visible mainline behavior.

Validated expectations:

- `app.agent.workflow-runtime.enabled=false` keeps the legacy path.
- `app.agent.workflow-runtime.enabled=true` emits the same stage order for `PLAN / RETRIEVE / TOOL / GUARD`.
- `WRITE` and SSE behavior remain owned by the existing mainline path.
- `tool_stage`, `rag_gate`, and `finance_guard` policy events are not duplicated.
- Market data workflow behavior remains unchanged.
- Empty-hit clarify behavior remains unchanged.
- Redis memory writes are not duplicated.

R2 acceptance established that runtime can wrap deterministic synchronous stages without taking over SSE.

## R3A: RuntimeTraceMapper / RuntimeEvalTraceMapper

R3A added pure mapping utilities.

`RuntimeTraceMapper`:

- Maps tool execution results into runtime `ToolTrace`.
- Keeps runtime trace independent from eval DTOs.
- Adds stable market data attrs such as:
  - `mock_mode=true`
  - `freshness=mock_non_realtime`
  - `tradable=false`

`RuntimeEvalTraceMapper`:

- Maps runtime `StageTrace` to `EvalChatStageTrace`.
- Maps runtime `ToolTrace` to `EvalChatToolTrace`.
- Keeps Eval Contract DTO conversion isolated from runtime execution.

Package boundary:

- `runtime.trace` must not depend on `com.travel.ai.eval.dto`.
- Eval mapper may depend on runtime trace and eval DTOs.

R3A did not change runtime behavior or JSON output.

## R3B: Mainline Internal StageTrace / ToolTrace

R3B made runtime traces available inside the mainline path when runtime is enabled.

Added internal `MainAgentTurnContext` fields:

- `runtimeStageTraces`
- `runtimeToolTraces`

Behavior:

- When `app.agent.workflow-runtime.enabled=true`, `runLinearStagesWithRuntime(ctx)` saves `WorkflowContext.stageTraces` into `ctx.runtimeStageTraces`.
- When runtime path is active and `stageTool(ctx)` receives a tool result, it maps the tool result into runtime `ToolTrace` and saves it into `ctx.runtimeToolTraces`.

R3B does not expose these traces externally.

It does not change:

- SSE event schema
- stage event order
- policy event generation
- Redis memory
- Eval endpoint response

The mainline runtime trace is now available internally, but it is not yet an external contract.

## R4A: EvalChatService Tool Trace Mapper Reuse

R4A aligned the eval endpoint's `market_data_explain` tool trace with runtime trace mapping.

Before R4A:

- `EvalChatService` manually constructed `EvalChatToolTrace`.

After R4A:

- `EvalChatService` constructs a runtime `ToolTrace`.
- It converts that trace through `RuntimeEvalTraceMapper`.
- The `/api/v1/eval/chat` execution path remains unchanged.

Important boundary:

- Eval still does not call `TravelAgent`.
- Eval still does not depend on SSE, Redis, ChatMemory, or LLM streaming.
- Only the DTO mapping path was aligned.

JSON behavior:

- `meta.tool_trace[].tool_name=market_data`
- `meta.tool_trace[].outcome=ok|timeout|...`
- `meta.tool_trace[].attrs.mock_mode=true`
- `meta.tool_trace[].attrs.freshness=mock_non_realtime`
- `meta.tool_trace[].attrs.tradable=false`

## R4B: EvalChatService Synthetic Stage Trace

R4B added minimal `meta.stage_trace` output to `/api/v1/eval/chat`.

The trace is synthetic. It is generated from the eval pipeline's existing `meta.stage_order` and current eval response state.

Generation rules:

- `stage_trace` is added only when `stage_order` exists and is non-empty.
- `stage_trace` is an enhancement field and does not replace `stage_order`.
- `stage_order` and `step_count` semantics remain unchanged.
- `RuntimeEvalTraceMapper` converts synthetic runtime `StageTrace` into Eval Contract DTOs.

Stage status rules:

- Executed normal stages use `status=success`.
- Plan-skipped stages use `status=skipped`.
- Skipped stages include `attrs.reason=skipped_by_plan`.
- Tool timeout uses `TOOL status=timeout` and `error_code=TOOL_TIMEOUT`.
- Tool error, circuit breaker, and rate limit use `TOOL status=failed` with the existing error code.

Current limitation:

- `elapsed_ms` is synthetic and currently uses `0`.
- It does not represent real stage runtime duration.

R4B does not change:

- `policy_events`
- `tool_trace`
- `workflow_id`
- `workflow_family`
- top-level `tool`
- `TravelAgent`
- SSE
- Redis memory

## Current Boundary

### Eval Trace Is Synthetic

`/api/v1/eval/chat` does not execute the real mainline runtime.

Its `meta.stage_trace` is derived from eval-owned state:

- `meta.stage_order`
- current eval tool result
- current eval error code

This keeps the eval endpoint deterministic and cheap to test.

### Mainline Trace Is Runtime Internal

When runtime is enabled, `/analysis/chat` records internal runtime traces:

- `MainAgentTurnContext.runtimeStageTraces`
- `MainAgentTurnContext.runtimeToolTraces`

These are not currently emitted in SSE or persisted as public API output.

### Eval Endpoint Does Not Call TravelAgent

This is intentional.

Calling `TravelAgent` from `EvalChatService` would introduce mainline side effects and nondeterminism:

- SSE lifecycle
- `ChatClient.stream()`
- Redis memory writes
- ChatMemory advisor behavior
- LLM streaming behavior
- test instability

Eval remains a contract-oriented endpoint, not a mainline replay path.

### MainSseChatAdapter Remains Future Work

`MainSseChatAdapter` is the future path for evaluating the real `/analysis/chat` stream.

It should be separate from `/api/v1/eval/chat`:

- `/api/v1/eval/chat` stays deterministic and JSON-based.
- `MainSseChatAdapter` can call `/analysis/chat`, parse SSE, and convert observed events into an eval-compatible result shape.

This gives the project two complementary evaluation modes:

- contract eval
- real mainline SSE eval

## Next Candidate Phases

### R5: MainSseChatAdapter

Add an adapter that calls the real `/analysis/chat` endpoint and parses SSE events.

Candidate responsibilities:

- observe `plan_parse`
- observe `stage`
- observe `policy`
- observe `citation`
- collect `data` tokens
- detect `done` / `error`
- build an eval observation result

This should not replace `/api/v1/eval/chat`.

### Move Nodes Out of TravelAgent

R2 currently keeps thin node wrappers close to `TravelAgent`.

A future phase may move nodes into dedicated classes:

- `PlanStageNode`
- `RetrieveStageNode`
- `ToolStageNode`
- `GuardStageNode`

This should be done incrementally and only after tests prove behavior parity.

### Real Stage Trace Duration

Runtime `StageTrace.elapsedMs` exists, but eval synthetic traces currently use `0`.

Future work:

- use measured runtime duration in mainline trace
- decide whether eval synthetic duration remains `0` or null
- avoid presenting synthetic eval duration as real execution time

### Runtime Trace Dashboard

Runtime traces can eventually power an internal observability view.

Candidate views:

- workflow id / family
- stage status counts
- tool outcome counts
- policy event counts
- timeout and failure distribution

This should consume trace data without changing the core workflow execution path.
