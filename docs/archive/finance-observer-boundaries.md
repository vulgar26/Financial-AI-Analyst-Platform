# Finance Observer Boundaries

This document defines architecture boundaries for Finance Observer.

Finance Observer is a financial observation and learning system. It is not an AI trading system, investment-advice system, or portfolio execution system.

Related roadmap:

- `docs/roadmap/finance-observer-design.md`

This document is design only. It does not introduce code, database migration, runtime changes, SSE changes, or eval execution changes.

## Boundary Summary

Recommended layering:

```text
Domain Layer
  -> Application Service Layer
  -> Provider / Infrastructure Layer

Provider / Infrastructure Layer
  -> Application Service Layer
  -> Agent Tool Layer
  -> ToolInvocationService
  -> Runtime / Agent path
```

Market data path target:

```text
MarketDataProvider
  -> MarketSnapshotService
  -> MarketDataTool
  -> ToolInvocationService
  -> Runtime / Agent path
```

Core rule:

Domain objects must remain independent from Runtime, Tool, SSE, and Eval concepts.

## 1. Domain Layer

The domain layer represents real business objects in the Finance Observer product.

Candidate domain objects:

- `WatchlistItem`
- `MarketSnapshot`
- `LearningJournalEntry`
- `ObservationContext`
- user observation intent / watch reason

### WatchlistItem

Represents a user-observed target.

Examples:

- stock
- ETF
- index
- sector
- theme

Core fields:

- symbol or identifier
- display name
- asset type
- category
- watch reason
- notes
- active / archived state
- owner user id

Meaning:

- "I am watching this because..."
- not "I own this"
- not "I should buy this"
- not "the system recommends this"

### MarketSnapshot

Represents an observation of market data at a point in time.

Core fields:

- symbol
- asset type
- price
- change percent
- volume
- volatility
- moving average
- max drawdown
- as-of timestamp
- source
- mock mode
- freshness
- tradable flag

Meaning:

- observed data for research
- not a tradable quote
- not execution data
- not a recommendation

### LearningJournalEntry

Represents a user's learning record.

Core fields:

- user id
- optional watchlist item id
- symbol
- entry type
- original observation
- later reflection
- learned lesson
- created / updated timestamps

Meaning:

- "what I thought"
- "what happened"
- "what I learned"

It should not become:

- trade log
- PnL ledger
- order history
- recommendation audit log

### ObservationContext

Represents the context needed to discuss an observed item.

Possible contents:

- watchlist item
- watch reason
- latest snapshot
- recent journal notes
- future news summary
- explicit data freshness metadata

Meaning:

- a read-only context bundle for research and learning
- not a trading decision context

### Domain Dependencies

Domain layer may depend on:

- primitive value objects
- timestamps
- simple enums or typed strings
- validation helpers that do not depend on infrastructure

Domain layer must not depend on:

- `LinearWorkflowRuntime`
- `WorkflowNode`
- `StageTrace`
- `ToolTrace`
- `MarketDataTool`
- `ToolInvocationService`
- SSE classes
- `ServerSentEvent`
- `EvalChatService`
- Eval DTOs
- database repositories
- HTTP controllers

## 2. Provider / Infrastructure Layer

The provider layer retrieves data from a source and reports provider metadata.

Candidate components:

- `MarketDataProvider`
- `MockMarketDataProvider`
- future external provider

### MarketDataProvider

Responsibilities:

- fetch data for a symbol
- return provider-specific metadata
- expose source name
- expose freshness
- expose as-of timestamp
- expose mock / non-realtime flags when applicable

Non-responsibilities:

- user ownership
- watchlist validation
- prompt assembly
- policy event construction
- SSE event construction
- Eval Contract mapping
- tool governance
- rate limiting at agent-tool level
- circuit breaker at agent-tool level

### MockMarketDataProvider

Initial provider.

Required metadata:

- `source=local_mock`
- `mock_mode=true`
- `freshness=mock_non_realtime`
- `tradable=false`

It should be deterministic enough for tests and obvious enough for users.

### Future External Provider

Future provider may connect to delayed or licensed data sources.

Even then, provider should only answer:

- what data was fetched
- from where
- at what time
- with what freshness
- with what limitations

It should not decide:

- whether the user can see a watchlist item
- what an agent should say
- whether to emit `finance_guard`
- how to phrase investment disclaimers

## 3. Application Service Layer

The application service layer coordinates domain objects, provider output, user ownership, and read-only observer semantics.

Candidate services:

- `WatchlistService`
- `MarketSnapshotService`
- future `ObservationContextService`

### WatchlistService

Responsibilities:

- enforce user ownership
- validate asset type
- validate symbol / identifier shape
- create watchlist item
- update category, notes, and watch reason
- deactivate / archive item
- list user-owned items

Non-responsibilities:

- market data fetching
- tool invocation
- prompt construction
- investment recommendation
- trading action

### MarketSnapshotService

Responsibilities:

- call `MarketDataProvider`
- normalize provider data into `MarketSnapshot`
- attach mock / non-realtime metadata
- attach read-only observer disclosure
- preserve `tradable=false`
- expose snapshot DTOs to API and future tool layer

Non-responsibilities:

- user watchlist ownership, unless explicitly invoked for a user watchlist view
- agent tool governance
- `tool_stage` policy events
- `finance_guard` policy events
- SSE streaming

### ObservationContextService

Future service.

Responsibilities:

- assemble read-only observation context
- combine watchlist item, watch reason, latest snapshot, future news, and future journal entries
- expose a context bundle to UI or agent adapters

Non-responsibilities:

- LLM streaming
- runtime scheduling
- order decision
- investment advice

## 4. Agent Tool Layer

The agent tool layer adapts application services into agent-observable tool output.

Current tool:

- `MarketDataTool`

### MarketDataTool Boundary

`MarketDataTool` is an Agent-facing adapter.

It is not the lowest-level market data source.

Target layering:

```text
MarketDataProvider
  -> MarketSnapshotService
  -> MarketDataTool
  -> ToolInvocationService
  -> Runtime / Agent path
```

Responsibilities:

- convert `MarketSnapshotService` output into tool observation text
- preserve mock / non-realtime disclosure
- preserve `tradable=false`
- expose stable tool name such as `market_data`
- keep output suitable for `ToolInvocationService` governance

Non-responsibilities:

- direct database ownership
- direct provider-specific logic
- user watchlist ownership
- circuit breaker
- rate limiter
- runtime trace mapping
- policy event construction outside the tool-stage boundary

`ToolInvocationService` remains responsible for:

- tool selection
- enabled policy
- rate limiting
- circuit breaker
- `ToolExecutor.execute(...)`
- tool outcome
- latency
- `tool_stage` policy event
- tool preface construction

Runtime / agent path remains responsible for:

- stage scheduling
- `StageTrace`
- `ToolTrace`
- `PolicyEvent` aggregation
- final prompt assembly
- SSE output

## 5. Policy / Guard Layer

Finance Observer policy should be reusable across UI, API, Tool, and Agent output.

Existing concepts:

- `finance_guard`
- market data mock disclosure
- not investment advice
- not usable for trading decisions
- non-tradable output

Future observer policy may add:

- observer-only disclosure
- read-only system disclosure
- data freshness warning
- source uncertainty warning
- journal reflection disclaimer

### Policy Responsibilities

Policy layer should provide centralized rules for:

- education / research only language
- non-investment-advice language
- mock / non-realtime disclosure
- no trading decision wording
- no buy / sell / hold instruction
- read-only observer semantics

Policy may produce:

- reusable disclosure text
- policy metadata
- policy events when in agent/eval paths

Policy should not be scattered across:

- controllers
- providers
- tools
- prompt assembly
- UI components

Some duplication may exist temporarily during migration, but the long-term target should be one policy vocabulary.

### finance_guard Boundary

`finance_guard` is the agent/eval policy-event representation of finance safety.

It should remain aligned with:

- mock mode
- freshness
- tradable flag
- disclosure requirement
- investment advice not allowed

Example attrs:

- `workflow_id=market_data_explain`
- `connector=market_data`
- `mock_mode=true`
- `freshness=mock_non_realtime`
- `tradable=false`
- `disclosure_required=true`
- `investment_advice_allowed=false`

### Observer Policy Boundary

Observer policy can exist outside agent execution.

Examples:

- API response disclosure
- UI badge text
- snapshot metadata
- journal warning

But it should share the same vocabulary as `finance_guard`:

- research only
- non-realtime
- non-tradable
- not investment advice
- manual verification required

## 6. Runtime / Agent Layer

Finance Observer should not directly modify the current runtime or streaming path in its first implementation phases.

Do not modify for D1/D2:

- `LinearWorkflowRuntime`
- `StageTrace`
- `ToolTrace`
- SSE event assembly
- `EvalChatService`
- `stageWrite(...)`
- `ChatClient.stream()`

The existing runtime remains the owner of deterministic agent-stage orchestration:

```text
PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE
```

Finance Observer should integrate through stable application services and agent-facing tools before it introduces any new workflow behavior.

Allowed near-term relationship:

```text
TravelAgent
  -> ToolInvocationService
  -> MarketDataTool
  -> MarketSnapshotService
```

Future observer workflows may reuse existing runtime primitives:

- `WorkflowTask`
- `WorkflowContext`
- `WorkflowNode`
- `NodeResult`
- `StageTrace`
- `ToolTrace`

Candidate future runtime-backed workflows:

- watchlist-aware analysis
- daily observer report
- risk summary
- journal reflection prompt

These are not MVP scope. They should be introduced only after Watchlist and Market Snapshot service boundaries are stable.

## 7. Eval Layer

Finance Observer should be eval-aware without making eval depend on the mainline runtime path.

Current boundary:

- `/api/v1/eval/chat` stays deterministic.
- `EvalChatService` does not call `TravelAgent`.
- Eval does not depend on SSE, Redis, ChatMemory, or `ChatClient.stream()`.

Future observer eval can check:

- `workflow_id`
- `workflow_family`
- `policy_events`
- `policy_events[].attrs`
- `tool_trace`
- market snapshot disclosure text
- mock / non-realtime metadata
- `tradable=false`
- not-investment-advice wording
- no buy / sell / hold recommendation

Example observer eval checks:

- market snapshot response includes `mock_mode=true`
- market snapshot response includes `freshness=mock_non_realtime`
- market snapshot response includes `tradable=false`
- agent output says data is not usable for trading decisions
- agent output does not provide buy / sell / hold instruction
- `finance_guard` appears when market data enters the agent path

D1/D2 rule:

- do not modify eval execution path
- do not make Watchlist or Market Snapshot depend on eval DTOs
- do not require observer APIs to emit Eval Contract fields directly

Eval integration should be a later adapter or test-harness concern.

## 8. Future AI Workflow Layer

Future AI workflows may build on Finance Observer data, but they are not MVP.

Candidate workflows:

- watchlist-aware analysis
- daily observer report
- risk summary
- journal reflection prompt
- observation-context summary
- news-risk explanation

These workflows should use application services as their data boundary:

```text
ObservationContextService
  -> WatchlistService
  -> MarketSnapshotService
  -> LearningJournalService
  -> future NewsService
```

Then an agent adapter may pass the result into runtime or prompt assembly.

Rules:

- AI workflows must remain read-only.
- AI workflows must not generate order instructions.
- AI workflows must include freshness and uncertainty disclosures where market data or news is involved.
- AI workflows should emit policy events only in agent/eval contexts, not inside domain or provider code.

Not MVP:

- automatic daily report generation
- journal automation
- news ingestion
- risk scoring automation
- autonomous workflow scheduling

## Cross-Layer Dependency Rules

Allowed direction:

```text
Controller
  -> Application Service
  -> Domain
  -> Provider abstraction

Agent Tool
  -> Application Service

Runtime / Agent
  -> ToolInvocationService
  -> Agent Tool
```

Forbidden direction:

```text
Domain -> Runtime
Domain -> Tool
Domain -> SSE
Domain -> Eval
Provider -> PolicyEvent
Provider -> PromptAssemblyService
Provider -> WatchlistService
Provider -> User authorization
MarketDataTool -> direct provider-specific sprawl
EvalChatService -> TravelAgent mainline execution
```

## Data Flow Examples

### Watchlist UI Flow

```text
HTTP Controller
  -> WatchlistService
  -> WatchlistRepository
  -> WatchlistItem
```

No runtime, tool, SSE, or eval dependency.

### Snapshot API Flow

```text
HTTP Controller
  -> MarketSnapshotService
  -> MarketDataProvider
  -> MarketSnapshot
```

The response includes mock / non-realtime / non-tradable metadata.

### Agent Market Data Flow

```text
TravelAgent
  -> ToolInvocationService
  -> MarketDataTool
  -> MarketSnapshotService
  -> MarketDataProvider
```

Tool governance and policy events remain in the agent path.

### Future Observation Context Flow

```text
ObservationContextService
  -> WatchlistService
  -> MarketSnapshotService
  -> LearningJournalService
  -> ObservationContext
```

This context can later be exposed to an agent adapter without making domain objects depend on runtime types.

## 9. Non-Goals

Do not introduce:

- buy / sell / hold recommendation
- trading APIs
- broker integration
- order placement
- portfolio allocation
- portfolio execution
- automatic investment decisioning
- real-time WebSocket quote streaming
- high-frequency quant workflows
- Kafka / MQ / microservice split
- runtime rewrite
- EvalChatService mainline execution dependency

Also do not introduce:

- hidden trading signals
- "recommended action" fields
- position sizing
- stop-loss / take-profit planning
- brokerage account linkage
- background trading jobs
- microservice rewrite as a prerequisite for MVP

## 10. Recommended First Implementation Boundary

The recommended first implementation should stay narrow:

- Watchlist backend
- mock `MarketSnapshotService`
- no real external provider
- no AI daily report
- no journal automation

### First Slice

Candidate first slice:

```text
Watchlist domain
  -> WatchlistService
  -> Watchlist repository
  -> /analysis/watchlist APIs
```

Then:

```text
MockMarketDataProvider
  -> MarketSnapshotService
  -> /analysis/market-snapshots APIs
```

Do not include in the first slice:

- `MarketDataTool` re-layering
- runtime node changes
- SSE changes
- eval execution changes
- real provider integration
- daily report generation
- journal automation

Acceptance for first slice:

- user can add / list / deactivate watchlist items
- user can record watch reason
- market snapshot is explicitly mock / non-realtime
- snapshot is explicitly non-tradable
- API wording stays research-only
- no endpoint contains a trading action

## Boundary Checklist

Before adding Finance Observer code, verify:

- Domain models have no runtime/tool/SSE/eval imports.
- Provider implementations do not check user permissions.
- Provider implementations do not construct prompt text.
- Provider implementations do not emit policy events.
- Application services enforce ownership and read-only semantics.
- `MarketDataTool` adapts service output rather than becoming the source of truth.
- Policy language is centralized or uses shared constants/helpers.
- Mock / non-realtime / non-tradable metadata appears in API and agent-facing output.
- No trading verb enters the API contract as an action.

## Rollback Principle

Each layer should remain independently revertible:

- Watchlist can exist without market snapshots.
- Market snapshots can exist without agent tool re-layering.
- Agent tool re-layering can be reverted without changing provider contracts.
- Runtime can stay disabled via feature flag.
- Eval remains independent from mainline execution.
