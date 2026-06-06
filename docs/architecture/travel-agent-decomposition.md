# TravelAgent Decomposition

This document records the current decomposition boundary for the legacy-compatible `TravelAgent` implementation that serves the Finance AI Analyst workflow.

The goal is not to delete `TravelAgent` in one step. The goal is to make it a smaller workflow adapter while preserving `/analysis/chat` behavior, SSE shape, Redis memory behavior, and Eval Contract boundaries.

## Completed Runtime Layer

The runtime layer is available and connected behind a feature flag:

- `LinearWorkflowRuntime`
- `WorkflowTask`
- `WorkflowContext`
- `WorkflowNode`
- `NodeResult`
- `NodeStatus`
- `StageTrace`
- `ToolTrace`

The deterministic pre-WRITE nodes are standalone classes:

- `PlanStageNode`
- `RetrieveStageNode`
- `ToolStageNode`
- `GuardStageNode`

Node boundary:

- Nodes own runtime execution semantics.
- Nodes return `NodeResult`.
- Nodes may write runtime attrs such as `run_retrieve`, `run_tool`, and `run_guard`.
- Nodes do not own business logic.
- Nodes do not emit SSE.
- Nodes do not write Redis.
- Nodes do not generate policy events directly.

## Completed Business Services

The following business logic has moved out of `TravelAgent`.

### PlanService

Owns:

- plan stage enabled / disabled decision
- `MainLinePlanProposer.proposePlanJson(...)`
- LLM plan failure fallback
- config-disabled fallback
- fallback plan JSON construction
- Appendix-E parse / repair
- `primary`, `fallback_template`, and `builtin_minimal` resolution
- plan parse meta assembly

Does not own yet:

- `physicalStageFlags(ctx)`
- `PlanStageNode` run flag flow
- `plan_parse` SSE event assembly

### RetrieveService

Owns:

- query rewrite
- vector search request construction
- `user_id` filter application
- `VectorStore.similaritySearch(...)`
- document merge / dedupe
- `promptBase`
- `citationBlock`
- `rewriteMs`
- `retrieveMs`

Does not own:

- current user lookup from `SecurityContextHolder`
- SSE emission
- guard decisions

### ToolInvocationService

Owns:

- tool selection
- market data priority over weather
- enabled policy
- circuit breaker allow / record
- rate limiter
- `ToolExecutor.execute(...)`
- tool preface construction
- `tool_stage` policy event construction

Does not own:

- `MainAgentTurnContext`
- runtime tool trace capture
- SSE
- Redis

### GuardDecisionService

Owns:

- empty-hit guard decision
- `rag_gate` policy event construction
- `finance_guard` policy event construction
- market data output guard predicate

Does not own:

- `MainAgentTurnContext`
- stage event generation
- prompt assembly

### PromptAssemblyService

Owns final prompt assembly:

- profile block
- tool preface
- finance output guard block
- plan block
- prompt base

The prompt order remains:

```text
profileBlock
toolPreface
financeOutputGuardBlock
planBlock
promptBase
```

Does not own:

- SSE
- Redis
- policy events
- runtime traces

## TravelAgent Remaining Responsibilities

Update (Pn2 done): the pre-WRITE orchestration (PLAN/RETRIEVE/TOOL/GUARD,
legacy + runtime paths) now lives in `MainChatWorkflowAdapter`; the mutable
turn state is `WorkflowTurnState` (replacing the old inner `MainAgentTurnContext`);
and `physicalStageFlags` resolution moved into `PlanService` (returned via
`PlanServiceResult.physicalStageFlags()`), so the adapter no longer re-parses
the plan.

`TravelAgent` now only remains responsible for the WRITE / SSE shell:

- `chat(...)` lifecycle
- request id and MDC lifecycle
- total timeout boundary
- delegation to `mainChatWorkflowAdapter.runPreWriteWorkflow(ctx)` (which owns the
  runtime feature-flag split internally)
- `stageWrite(...)`
- `ChatClient.stream()`
- SSE stream assembly
- heartbeat, done, and error handling
- Redis memory special write for empty-hit clarify
- policy event aggregation into SSE
- `plan_parse` SSE event assembly

Removed entirely: the legacy `WeatherTool` (a `travel`-era example `@Tool`) and
`TravelAgent.guessCityForWeather(...)`. The only governed tool is now
`MarketDataTool` (mock).

These remaining responsibilities are intentionally kept together until their risks are isolated.

## Current Non-Goals

Do not split these in the near term:

- `stageWrite(...)`
- `ChatClient.stream()`
- Redis memory coordination
- SSE event assembly
- `EvalChatService`

Reasons:

- SSE uses reactive stream composition and is sensitive to duplicate subscription.
- Redis memory has a known duplicate-write risk if moved into reactive completion hooks incorrectly.
- Eval endpoint is deterministic and intentionally does not call `TravelAgent`.
- Public event shape must not drift accidentally.

## Completed Phases (previously "next candidates")

### WorkflowTurnState Extraction — DONE

`MainAgentTurnContext` was moved out of `TravelAgent` into a dedicated
`com.travel.ai.agent.state.WorkflowTurnState` model.

### MainChatWorkflowAdapter — DONE

`com.travel.ai.agent.workflow.MainChatWorkflowAdapter` owns PLAN / RETRIEVE /
TOOL / GUARD execution (both legacy and runtime paths) and returns state ready
for WRITE. It does not own `stageWrite(...)`.

### PlanService Phase Pn2 — DONE

Physical stage flag resolution moved into `PlanService`: it parses the effective
`planJson`, runs `PlanPhysicalStagePolicy.resolve(...)`, and returns the flags via
`PlanServiceResult.physicalStageFlags()`. The adapter consumes them directly
instead of re-parsing the plan.

## Next Candidate Phases

### MainSseChatAdapter

Future eval integration for the real `/analysis/chat` stream:

- call `/analysis/chat`
- parse SSE
- collect `plan_parse`, `stage`, `policy`, `citation`, `data`, `done`, and `error`
- convert the observed stream into an eval-compatible observation

This should not replace `/api/v1/eval/chat`.

### WriteStreamService

Long-term candidate only.

It would own `stageWrite(...)`, `ChatClient.stream()`, timeout handling, usage logging, and WRITE stage completion.

This is not a near-term step because it crosses the highest-risk reactive streaming boundary.

## Risk Boundaries

Main risks to protect:

- SSE multi-subscription
- Redis memory duplicate write
- duplicate stage events
- duplicate policy events
- `finalPromptForLlm` format drift
- market data disclaimer removal
- physical stage flag drift
- Eval endpoint accidentally depending on mainline side effects

## Rollback Principles

- Every extracted service should be individually revertible.
- Runtime scheduling can be disabled with `app.agent.workflow-runtime.enabled=false`.
- Refactors should not require DB migration.
- Refactors should not change Redis keys.
- Refactors should not change public controller routes.
- Refactors should not make `/api/v1/eval/chat` call `TravelAgent`.
