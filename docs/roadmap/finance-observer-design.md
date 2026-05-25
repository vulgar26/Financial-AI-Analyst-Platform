# Finance Observer Design

Phase D1 is design only. It records the intended product and architecture direction for a Finance Observer layer on top of the current Finance AI Analyst system.

This document does not propose code changes, database migrations, runtime rewrites, SSE changes, or eval execution-path changes.

## 1. Product Positioning

Finance Observer is a financial observation and learning system.

It is not:

- AI stock picking
- investment advice
- automated trading
- portfolio management
- high-frequency quant infrastructure
- real-time market data streaming

It is:

- education / research only
- read-only first
- non-tradable
- long-term observation
- market record keeping
- risk awareness
- AI-assisted summarization
- learning journal support

The system should help users answer questions such as:

- Why am I watching this company, ETF, index, or sector?
- What changed since the last observation?
- What risks should I keep tracking?
- Which data points need manual verification?
- What did I learn from this market event?

All user-facing and agent-facing output must preserve the existing finance safety posture:

- content is for research and education
- not investment advice
- no buy / sell / hold instruction
- no execution or trading capability
- market data may be mock or non-realtime unless explicitly upgraded later

## 2. MVP Scope

The first usable Finance Observer MVP should focus on Watchlist plus mock / non-realtime Market Snapshot.

### In Scope

Watchlist:

- add an observed item
- remove an observed item
- list observed items
- categorize observed items
- store a user note explaining why the item is being watched
- support symbols for ETF, index, stock, and sector / theme

Market Snapshot:

- read-only snapshot view
- current price
- change percent
- volume
- simple volatility placeholder
- moving average placeholder
- explicitly marked mock / non-realtime

AI Integration:

- allow existing `/analysis/chat` to discuss watchlist items through future retrieval / tool context
- preserve policy events and finance output guard
- preserve market data mock disclosure

### Out of Scope For MVP

- live WebSocket market data
- real brokerage integration
- order placement
- account balances
- portfolio PnL
- automated recommendations
- alerting based on trading signals
- news ingestion
- daily generated reports
- learning journal automation

## 3. Module Breakdown

Recommended module shape:

- Watchlist
- Market Snapshot
- Market Data Provider
- Observer Policy
- Future News Feed
- Future Daily Report
- Future Learning Journal

### Watchlist

Owns user-curated observation targets.

Responsibilities:

- store watched items
- store category and note
- distinguish asset type
- preserve user ownership
- expose read-only observation state to future agent tools

Watchlist item examples:

- `AAPL` as stock
- `SPY` as ETF
- `NASDAQ-100` as index
- `AI infrastructure` as sector / theme

### Market Snapshot

Owns normalized market observation output.

Responsibilities:

- request market data from `MarketDataProvider`
- normalize provider output
- add explicit freshness / mock / tradability metadata
- provide data to UI and future tool calls

### Market Data Provider

Provider abstraction for market data.

Initial provider:

- mock local provider
- non-realtime
- deterministic enough for tests

Future providers:

- external data provider
- cached provider
- delayed quote provider

Provider output must expose metadata:

- `mock_mode`
- `freshness`
- `as_of`
- `tradable=false`
- `source`

### Observer Policy

Policy layer for observer-specific safety and disclosure.

Responsibilities:

- enforce education / research wording
- mark non-realtime data
- mark non-tradable output
- prevent trading instructions
- emit policy events where relevant

This should align with current `finance_guard` semantics instead of creating a second disconnected safety model.

## 4. DB Schema Draft

No migration is introduced in Phase D1. This is a schema draft only.

### `watchlist_items`

Purpose: store user-owned observation targets.

Candidate columns:

- `id` UUID primary key
- `user_id` varchar not null
- `symbol` varchar not null
- `display_name` varchar null
- `asset_type` varchar not null
- `category` varchar null
- `watch_reason` text null
- `notes` text null
- `active` boolean not null default true
- `created_at` timestamp not null
- `updated_at` timestamp not null

Candidate `asset_type` values:

- `stock`
- `etf`
- `index`
- `sector`
- `theme`
- `other`

Indexes:

- `(user_id, active)`
- `(user_id, symbol)`
- `(user_id, category)`

Compatibility rule:

- avoid hard enum constraints in the first migration unless the project already has a stable enum migration pattern
- prefer application-level validation for early evolution

### `market_snapshots`

Purpose: optional persisted snapshot history. This may be postponed until snapshots need history.

Candidate columns:

- `id` UUID primary key
- `symbol` varchar not null
- `asset_type` varchar null
- `price` numeric null
- `change_pct` numeric null
- `volume` numeric null
- `volatility` numeric null
- `moving_average` numeric null
- `max_drawdown` numeric null
- `as_of` timestamp null
- `source` varchar not null
- `mock_mode` boolean not null default true
- `freshness` varchar not null
- `tradable` boolean not null default false
- `raw_payload_json` jsonb null
- `created_at` timestamp not null

Indexes:

- `(symbol, as_of desc)`
- `(created_at desc)`

MVP recommendation:

- do not persist snapshots initially unless UI or reports need history
- start with provider-generated read-only snapshots

### `learning_journal_entries`

Future only.

Candidate columns:

- `id` UUID primary key
- `user_id` varchar not null
- `watchlist_item_id` UUID null
- `symbol` varchar null
- `entry_type` varchar not null
- `content` text not null
- `learned` text null
- `created_at` timestamp not null
- `updated_at` timestamp not null

## 5. API Contract Draft

No controller changes are introduced in Phase D1. These are candidate contracts.

Recommended base path:

- `/analysis/watchlist`
- `/analysis/market-snapshots`

`/finance/**` aliases may be added later if consistent with existing alias strategy.

### Watchlist APIs

Create item:

```http
POST /analysis/watchlist
Content-Type: application/json
```

Request:

```json
{
  "symbol": "AAPL",
  "display_name": "Apple Inc.",
  "asset_type": "stock",
  "category": "mega_cap_tech",
  "watch_reason": "Track earnings quality and AI capex exposure",
  "notes": "Education/research only"
}
```

Response:

```json
{
  "id": "uuid",
  "symbol": "AAPL",
  "display_name": "Apple Inc.",
  "asset_type": "stock",
  "category": "mega_cap_tech",
  "watch_reason": "Track earnings quality and AI capex exposure",
  "notes": "Education/research only",
  "active": true,
  "created_at": "2026-05-25T00:00:00Z",
  "updated_at": "2026-05-25T00:00:00Z"
}
```

List items:

```http
GET /analysis/watchlist?active=true&category=mega_cap_tech
```

Delete / deactivate item:

```http
DELETE /analysis/watchlist/{id}
```

MVP recommendation:

- implement soft delete via `active=false`
- do not physically delete by default

### Market Snapshot APIs

Single snapshot:

```http
GET /analysis/market-snapshots/{symbol}
```

Response:

```json
{
  "symbol": "AAPL",
  "asset_type": "stock",
  "price": 123.45,
  "change_pct": 0.0,
  "volume": 1000000,
  "volatility": null,
  "moving_average": null,
  "max_drawdown": null,
  "as_of": "2026-05-25T00:00:00Z",
  "source": "local_mock",
  "mock_mode": true,
  "freshness": "mock_non_realtime",
  "tradable": false,
  "disclosure": "Mock, non-realtime market data for education and research only. Not investment advice and not usable for trading decisions."
}
```

Batch snapshot:

```http
POST /analysis/market-snapshots:batch
Content-Type: application/json
```

Request:

```json
{
  "symbols": ["AAPL", "SPY", "QQQ"]
}
```

Response:

```json
{
  "items": [
    {
      "symbol": "AAPL",
      "price": 123.45,
      "change_pct": 0.0,
      "mock_mode": true,
      "freshness": "mock_non_realtime",
      "tradable": false
    }
  ]
}
```

## 6. Service Layering

Recommended layering:

```text
Controller
  -> WatchlistService
  -> WatchlistRepository

Controller
  -> MarketSnapshotService
  -> MarketDataProvider
```

Agent/tool path:

```text
ToolInvocationService
  -> MarketDataTool
  -> MarketSnapshotService
  -> MarketDataProvider
```

This direction is intentional.

`MarketDataTool` should not become the lowest-level market data source. It should be an agent-facing wrapper over observer market data services.

### WatchlistService

Responsibilities:

- validate symbol and asset type
- enforce user ownership
- create / update / deactivate watchlist items
- provide read models for UI and future agent context

Non-responsibilities:

- market quote retrieval
- AI summary generation
- trading decisions

### MarketSnapshotService

Responsibilities:

- normalize market data
- include freshness and source metadata
- include mock / non-realtime disclosure
- expose read-only snapshot DTOs

Non-responsibilities:

- tool governance
- policy event generation
- order execution
- real-time streaming

### MarketDataProvider

Responsibilities:

- provider-specific quote lookup
- provider metadata
- mock provider in first phase

Non-responsibilities:

- user ownership
- watchlist persistence
- prompt formatting
- policy events

## 7. Relationship To Existing MarketDataTool / Runtime

Current `MarketDataTool` already exercises the governed tool path and returns local mock market data.

Future target layering:

```text
MarketDataProvider
  -> MarketSnapshotService
  -> MarketDataTool
  -> ToolInvocationService
  -> TravelAgent runtime path
```

Rationale:

- `MarketDataProvider` owns source-specific data retrieval.
- `MarketSnapshotService` owns finance observer normalization.
- `MarketDataTool` adapts snapshot data into agent-observable tool output.
- `ToolInvocationService` owns governance, circuit breaker, rate limit, outcome, latency, preface, and `tool_stage`.
- Runtime and eval trace mappers continue to observe tool outcomes without knowing provider details.

No D1 change should modify:

- `LinearWorkflowRuntime`
- `WorkflowNode`
- `StageTrace`
- `ToolTrace`
- `RuntimeTraceMapper`
- `RuntimeEvalTraceMapper`
- `EvalChatService`
- SSE

Future observer workflows may use the existing runtime model for tasks such as:

- generate daily observation summary
- summarize watchlist changes
- classify news risk
- create learning journal prompts

But D1 does not introduce new runtime nodes.

## 8. Mock / Non-Realtime Strategy

The first phase must be explicit about data quality.

Required snapshot metadata:

- `mock_mode=true`
- `freshness=mock_non_realtime`
- `tradable=false`
- `source=local_mock`
- `as_of`

Required disclosure:

```text
Mock, non-realtime market data for education and research only. Not investment advice and not usable for trading decisions.
```

Agent-facing output must preserve the existing market data safety language:

- data is mock / non-realtime
- data cannot be used for trading decisions
- content is not investment advice
- no buy / sell / hold instruction

Testing should assert these fields before any future provider is introduced.

## 9. Risks And Non-Goals

### Risks

Product risks:

- users may interpret observations as recommendations
- mock data may be mistaken for real-time data
- watchlist may be mistaken for portfolio management

Technical risks:

- duplicating market data logic between `MarketDataTool` and future snapshot services
- bypassing tool governance when adding observer APIs
- introducing policy events in multiple places
- leaking trading semantics into DTO names or UI text
- making eval depend on mainline side effects

Mitigations:

- use explicit wording: observer, research, learning, non-tradable
- keep provider metadata in every snapshot response
- keep trading actions out of API contracts
- keep tool governance in `ToolInvocationService`
- keep eval endpoint deterministic

### Non-Goals

Do not design:

- order placement
- broker integration
- auto trading
- portfolio allocation
- buy / sell / hold recommendation engine
- high-frequency trading
- real-time WebSocket quotes
- Kafka / MQ / microservice split
- Temporal / DAG rewrite

## 10. Phased Roadmap

### Phase D1: Design Only

Deliverable:

- this document

No code, schema, runtime, eval, SSE, or config changes.

### Phase D2: Watchlist Backend MVP

Candidate work:

- `watchlist_items` migration
- Watchlist DTOs
- Watchlist repository
- Watchlist service
- `/analysis/watchlist` APIs
- tests for ownership, create/list/deactivate, and validation

Constraints:

- no market data persistence required
- no trading fields
- no recommendation fields

### Phase D3: Market Snapshot Mock Service

Candidate work:

- `MarketDataProvider` interface
- `MockMarketDataProvider`
- `MarketSnapshotService`
- `/analysis/market-snapshots/**` APIs
- snapshot DTOs with mock / freshness / tradable metadata

Constraints:

- no real-time feed
- no WebSocket
- no external provider dependency required

### Phase D4: MarketDataTool Re-layering

Candidate work:

- make `MarketDataTool` call `MarketSnapshotService`
- preserve existing tool output shape and disclosure semantics
- preserve `ToolInvocationService` governance
- preserve `tool_trace` and `finance_guard` behavior

Constraints:

- no change to eval execution path unless separately planned
- no change to SSE streaming

### Phase D5: Watchlist-Aware Analysis

Candidate work:

- allow `/analysis/chat` to reference user watchlist context
- optionally expose watchlist context as retrieval or prompt prefix
- preserve `PromptAssemblyService` ordering and finance guard behavior

Constraints:

- no automatic advice
- no trading decisions

### Phase D6: Learning Journal

Candidate work:

- journal entry APIs
- link journal entries to watchlist items
- record observations and lessons learned

Constraints:

- user-authored first
- AI-assisted summaries later

### Phase D7: News Feed And Daily Report

Candidate work:

- delayed or mock news feed
- risk classification
- AI daily observer report
- policy events for data freshness and uncertainty

Constraints:

- no real-time trading signal feed
- no automated portfolio decision

## Design Boundary Summary

Finance Observer should evolve as a read-only research and learning layer.

It should reuse the existing agent runtime, trace, tool governance, and policy model where appropriate, but it should not force runtime, SSE, Redis, or eval changes in the first phases.

The safest path is:

```text
Watchlist
  -> MarketSnapshotService
  -> MarketDataProvider
  -> MarketDataTool re-layering
  -> agent-aware observer workflows
```

The system remains Finance AI Analyst / Finance Observer, not an AI trading system.
