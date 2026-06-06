# Agent Platform Technical Roadmap

This document records the technical evolution path for the Agent Platform behind the current Finance AI Analyst / Finance Observer system.

It is an internal architecture roadmap. It does not introduce code, configuration, database migration, runtime changes, SSE changes, or eval execution-path changes.

Related documents:

- `docs/roadmap/finance-observer-design.md`
- `docs/architecture/finance-observer-boundaries.md`
- `docs/architecture/travel-agent-decomposition.md`
- `docs/architecture/workflow-runtime-progress.md`

## 1. Current Platform State

The platform has moved beyond a plain chat application. It now has a deterministic finance-analysis workflow, runtime skeleton, trace model, eval contract, and extracted business services.

Implemented runtime and trace components:

- `LinearWorkflowRuntime`
- `WorkflowNode`
- `WorkflowTask`
- `WorkflowContext`
- `NodeResult`
- `NodeStatus`
- `PlanStageNode`
- `RetrieveStageNode`
- `ToolStageNode`
- `GuardStageNode`
- `StageTrace`
- `ToolTrace`
- `RuntimeTraceMapper`
- `RuntimeEvalTraceMapper`

Implemented eval contract work:

- Eval Contract V1
- `meta.stage_trace`
- `meta.tool_trace`
- `meta.policy_events`
- workflow metadata support
- runtime-to-eval trace mapping

Extracted business services:

- `PlanService`
- `RetrieveService`
- `ToolInvocationService`
- `GuardDecisionService`
- `PromptAssemblyService`

Current mainline workflow:

```text
PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE
```

`TravelAgent` currently remains the mainline wrapper around this workflow, but most pre-WRITE business logic has moved into smaller services.

## 2. Current System Layering

### Business Domain

Business domain covers Finance AI Analyst and Finance Observer concepts.

Current and planned domain areas:

- Finance Observer
- Watchlist
- Market Snapshot
- Learning Journal
- Observation Context
- future News Feed
- future Daily Report

This layer should model finance observation and learning, not trading or investment decisioning.

### Agent Workflow

Agent workflow is the deterministic execution sequence:

```text
PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE
```

Responsibilities:

- plan generation and parse/fallback
- RAG retrieval
- governed tool execution
- guard decisions and policy events
- final LLM write stage

### Agent Platform

Agent platform provides reusable execution and observability primitives:

- runtime
- workflow nodes
- node results
- stage trace
- tool trace
- tool governance
- eval contract alignment
- trace mappers

This layer should stay generic enough to support future observer workflows without becoming a generic agent framework too early.

### Streaming Layer

Streaming layer owns high-risk user-facing behavior:

- SSE
- `ChatClient.stream()`
- heartbeat
- done / error events
- Redis memory side effects
- reactive stream subscription behavior

This layer is intentionally not being refactored aggressively.

## 3. Stable Boundaries

The following boundaries are now stable enough to build on.

### ToolInvocationService

Owns tool governance:

- tool selection
- enabled policy
- circuit breaker
- rate limiter
- `ToolExecutor.execute(...)`
- tool outcome
- latency
- tool preface construction
- `tool_stage` policy event construction

It does not own runtime trace capture, SSE, Redis, or `MainAgentTurnContext`.

### PromptAssemblyService

Owns prompt assembly:

- profile block
- tool preface
- finance output guard block
- plan block
- prompt base

It preserves the stable prompt order:

```text
profileBlock -> toolPreface -> financeOutputGuardBlock -> planBlock -> promptBase
```

### GuardDecisionService

Owns guard decisions:

- empty-hit clarify behavior
- `rag_gate`
- `finance_guard`
- market-data output guard predicate

It returns decisions and policy events. It does not write agent state or emit SSE.

### RetrieveService

Owns RAG retrieval:

- query rewrite
- vector search request construction
- `user_id` filter
- vector search
- document merge / dedupe
- `promptBase`
- `citationBlock`
- retrieval timing

It does not own current-user lookup, SSE, Redis, or guard decisions.

### PlanService

Owns plan generation and normalization:

- plan-stage enabled decision
- `MainLinePlanProposer`
- LLM fallback
- disabled fallback
- fallback plan JSON
- Appendix-E parse / repair
- `primary`, `fallback_template`, and `builtin_minimal` resolution
- plan parse meta output

It does not yet own physical stage flags in Pn1.

### TravelAgent

`TravelAgent` is now primarily:

- workflow adapter
- state writer
- runtime feature flag bridge
- SSE adapter
- Redis memory special-case coordinator

It remains legacy-compatible and currently serves the Finance AI Analyst workflow.

## 4. Current Non-Touch Areas

Do not refactor these in the near term:

- `stageWrite(...)`
- `ChatClient.stream()`
- SSE event assembly
- Redis memory behavior
- `EvalChatService` execution path
- DB / migration
- multi-agent workflows
- MQ / distributed async workflow

Reasons:

- SSE and `ChatClient.stream()` are sensitive to reactive multi-subscription behavior.
- Redis memory has duplicate-write risk.
- Eval execution is intentionally deterministic and separate from mainline SSE.
- DB migration should follow product features, not platform refactors.
- Multi-agent and MQ would create infrastructure complexity before product need is proven.

## 5. Near-Term Technical Roadmap

### WorkflowTurnState

Move `MainAgentTurnContext` out of `TravelAgent` into a dedicated state model.

Goal:

- reduce inner-class coupling
- make service-result writeback explicit
- prepare for `MainChatWorkflowAdapter`

Constraints:

- no SSE changes
- no Redis behavior changes
- no runtime behavior changes

### MainChatWorkflowAdapter

Introduce an adapter that owns PLAN / RETRIEVE / TOOL / GUARD execution and returns a state ready for WRITE.

Initial scope:

- choose legacy or runtime path
- call existing stage adapters
- preserve stage event order
- preserve policy event aggregation

Out of scope:

- `stageWrite(...)`
- SSE composition
- Redis memory

### PlanService Pn2

Evaluate whether physical stage flags should move into `PlanServiceResult`.

Potential output fields:

- `runRetrieve`
- `runTool`
- `runGuard`

Benefits:

- removes `physicalStageFlags(ctx)` from `TravelAgent`
- avoids re-parsing plan in the adapter

Risk:

- stage flags directly control RETRIEVE / TOOL / GUARD execution
- market-data and empty-hit behavior could regress

### ObservationContextService

Add a Finance Observer application service that assembles read-only context from:

- watchlist item
- watch reason
- market snapshot
- future journal entries
- future news summary

This should support future watchlist-aware analysis without making domain objects depend on runtime or tool types.

### TravelAgent Wrapper Role

Continue lowering `TravelAgent` into a legacy-compatible wrapper.

Target:

- keep public behavior stable
- delegate pre-WRITE business work
- keep high-risk streaming behavior in one place until it is safe to move

## 6. Mid-Term Technical Roadmap

### MainSseChatAdapter

Add an eval adapter for the real `/analysis/chat` SSE mainline.

Responsibilities:

- call `/analysis/chat`
- parse SSE events
- observe `plan_parse`
- observe `stage`
- observe `policy`
- observe `citation`
- collect `data` tokens
- detect `done` / `error`
- produce an eval-compatible observation

This should not replace `/api/v1/eval/chat`.

### Runtime Trace Dashboard / Debug View

Use existing trace data for internal observability:

- stage status
- stage duration
- tool outcome
- policy event counts
- workflow id / family
- error code distribution

This should consume trace data without changing workflow execution.

### Redis Layered Memory

Design memory layering before moving memory writes:

- short-term chat memory
- profile memory
- future observer memory
- learning journal

Goal:

- avoid duplicate writes
- clarify which memory source enters prompts
- preserve current Redis behavior until a new design is tested

### Market Data Relayering

Align Finance Observer market data path:

```text
MarketDataProvider
  -> MarketSnapshotService
  -> MarketDataTool
  -> ToolInvocationService
```

Mid-term work:

- `MarketDataProvider`
- `MockMarketDataProvider`
- `MarketSnapshotService`
- re-layer `MarketDataTool`

Constraints:

- preserve mock / non-realtime disclosure
- preserve `finance_guard`
- preserve `tool_trace`
- no real-time WebSocket quotes

## 7. Long-Term Technical Roadmap

Long-term items should wait until product demand is clear.

### Async Workflow / Job Orchestration

Potential use cases:

- daily observer report
- scheduled watchlist summary
- background news digest

Do not introduce MQ or async orchestration just for Watchlist MVP.

### Multi-Agent Workflow

Potential future agents:

- market observer
- news summarizer
- journal reflection assistant
- risk explainer

This should build on stable single-workflow primitives first.

### Workflow Event Store

Potential use:

- persist workflow traces
- replay reports
- debug long-running observer workflows

Not needed for current `/analysis/chat` or Watchlist MVP.

### Runtime Node Registry

Potential use:

- registered workflow nodes
- named workflows
- configurable observer workflows

Avoid introducing this before there are multiple real workflows.

### Provider Connector Abstraction

Potential use:

- market data providers
- news providers
- filing / announcement providers

This should start with simple interfaces, not a plugin platform.

### Observer Daily Report Pipeline

Potential workflow:

```text
Watchlist -> MarketSnapshot -> News Summary -> Risk Summary -> Daily Report
```

This should remain research-only and non-tradable.

## 8. Technical Roadmap Principles

Principles:

- business value first, architecture second
- do not refactor runtime for Watchlist
- do not introduce MQ for hypothetical multi-agent work
- defer SSE and Redis refactors because they are high-risk
- keep all new trace and eval fields optional
- keep every phase independently revertible
- preserve deterministic eval endpoint behavior
- preserve current `/analysis/chat` external behavior
- avoid turning Finance Observer into a trading system

Practical rules:

- add application services before runtime changes
- add provider abstractions before tool re-layering
- add eval checks after public behavior exists
- add dashboards after trace data is stable
- move streaming only after adapter boundaries are proven

## 9. Recommended Near-Term Rhythm

Recommended split:

- 70% business line: Finance Observer MVP
- 30% platform line: TravelAgent adapter-ization and technical debt containment

### Business Line

Focus:

- Watchlist backend
- mock Market Snapshot
- read-only observer semantics
- watch reason / observation intent
- explicit mock / non-realtime disclosure

Avoid:

- real provider integration too early
- AI daily report too early
- journal automation too early
- trading semantics

### Platform Line

Focus:

- `WorkflowTurnState`
- `MainChatWorkflowAdapter`
- PlanService Pn2 decision
- runtime trace cleanup
- documentation and tests around existing boundaries

Avoid:

- `stageWrite(...)` refactor
- Redis memory rewrite
- MQ
- multi-agent runtime

## Roadmap Summary

The platform should evolve in two coordinated tracks:

```text
Finance Observer product track:
  Watchlist -> Mock Market Snapshot -> Observation Context -> Tool relayering -> AI observer workflows

Agent platform track:
  WorkflowTurnState -> MainChatWorkflowAdapter -> trace/debug improvements -> MainSseChatAdapter
```

The next practical step should prioritize Finance Observer MVP while keeping platform work focused on shrinking `TravelAgent` safely.
