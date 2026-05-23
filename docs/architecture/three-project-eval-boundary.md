# Three-Project Eval Boundary

本文说明 `travel-ai-planner` / finance-agent、`Vagent`、`vagent-eval` 三个项目之间的 eval 边界和契约关系。目标是为后续工程实施、回归评测和面试讲解提供一份稳定口径：跨项目可以共享协议和观测语义，但每个项目仍按自己的业务边界独立实现。

## 1. 三个项目职责

### travel-ai-planner / finance-agent

`travel-ai-planner` 是业务 target。当前主线已经转向金融研究/分析语义，也可称为 finance-agent 方向；历史 travel 命名仅作为兼容背景保留。

它负责：

- 执行真实业务 runtime：鉴权、会话、RAG、工具、guard、SSE 输出。
- 暴露项目内 eval endpoint：`POST /api/v1/eval/chat`，用于非流式、结构化、可自动判定的回归评测。
- 维护金融领域的业务约束，例如 finance guard、market data mock、非投资建议声明、数据时效提示。
- 将主线 runtime 中稳定、可复用的观测语义映射到 eval response，例如 stage、tool outcome、policy event、error code。

它不负责实现独立评测平台，也不应该把自己的 agent 实现强加给其他 target。

### Vagent

`Vagent` 是另一个业务 target。它和 `travel-ai-planner` 一样可以被 eval harness 调用，但它有自己的 RAG、工具、guardrail、SSE 和业务实现。

它负责：

- 暴露同类 `POST /api/v1/eval/chat` target endpoint。
- 返回与 eval harness 兼容的最小 response contract。
- 按自身能力返回 retrieval、tool、guardrails、evidence map、low confidence 等观测字段。
- 在需要时选择性兼容共享 meta 字段，例如 `workflow_id` 或空 `policy_events`。

它不需要实现 finance guard，也不需要复制 `travel-ai-planner` 的金融 workflow。

### vagent-eval

`vagent-eval` 是独立 eval harness。它不是业务系统，也不是某个 target 的内部测试类。

它负责：

- 调度 dataset，对多个 target 调用 `{base_url}/api/v1/eval/chat`。
- 校验 target response 的最小结构契约。
- 根据 dataset 的 `expected_behavior`、`requires_citations`、tool expectation、membership 等规则给出 verdict。
- 保存 result、debug 摘要和 target 返回的 `meta` 快照。
- 生成 run report 和 compare 输出。

它不负责执行真实 Agent，不负责拼 SSE，不负责持有 target 的 prompt、tool registry、RAG 策略或 memory。

## 2. 三层边界

### 主线 runtime

主线 runtime 是业务产品真正服务用户的链路。它可以是 SSE、WebSocket、同步 HTTP 或异步任务。

在 `travel-ai-planner` 中，主线 runtime 包含：

- `PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE` 阶段推进。
- 会话、memory、用户隔离、JWT、限流和超时。
- RAG 检索、工具调用、finance guard、SSE 事件拼接。
- 面向用户的最终答案和流式交互体验。

主线 runtime 优先满足产品体验和业务正确性，不应该为了 eval 直接暴露内部实现细节。

### 项目内 eval endpoint

项目内 eval endpoint 是 target 侧提供的评测适配层，典型路径是：

```text
POST /api/v1/eval/chat
```

它的职责是把业务 target 的运行结果压缩成稳定 JSON：

- 非流式返回。
- 字段命名使用 snake_case。
- 顶层包含最小 contract。
- `meta` 放置 target-specific 的观测字段。
- 不要求与主线 SSE 完全同形，但关键语义要可对齐。

项目内 eval endpoint 不是新的产品 runtime，也不应该复制一套复杂 Agent 架构。它只是业务 target 面向 harness 的协议边界。

### 独立 eval harness

独立 eval harness 是跨 target 的执行、判定和对比系统。

它只依赖 target 暴露的 eval response，不依赖 target 的 Java 类、prompt、SSE 拼接或内部服务。它应该尽量宽松接收可选 meta 字段，严格校验最小 contract 和会影响判定的字段类型。

## 3. 应该共享的内容

跨项目应该共享的是协议语义，不是实现。

### stage

建议共享稳定阶段名，例如：

```text
PLAN
RETRIEVE
TOOL
GUARD
WRITE
```

target 可以在 `meta.stage_order` 中返回本次实际经过的阶段子序列。不同项目内部可以有更多细分步骤，但跨项目 compare 只依赖稳定阶段语义。

### tool outcome

工具结果应共享 outcome 语义，例如：

```text
ok
timeout
error
rate_limited
disabled_by_circuit_breaker
skipped
```

顶层 `tool` 块用于 eval 判定，`meta.tool_*` 用于治理和排障。不同项目的工具实现和 registry 不共享。

### policy event

policy event 用于表达安全、RAG、工具、金融合规、checkpoint 等策略决策轨迹。

推荐最小形态：

```json
{
  "policy_type": "finance_guard",
  "stage": "tool",
  "behavior": "answer",
  "rule_id": "market_data_mock_non_realtime",
  "error_code": null,
  "attrs": {
    "workflow_id": "market_data_explain"
  }
}
```

`policy_type/stage/behavior/rule_id/error_code` 是稳定归因字段，`attrs` 承载业务扩展属性。

### error code

`error_code` 应在跨项目上保持机器可读、稳定、可聚合。它可以出现在顶层，也可以在 `meta.policy_events[].error_code` 中表达某条策略事件的局部归因。

建议原则：

- 顶层 `error_code` 表示本次 response 的最终归因。
- policy event 的 `error_code` 表示该策略事件的局部归因。
- 不把自然语言 message 当作 error code。

### EvalRunResult schema

`vagent-eval` 的 result schema 应作为 harness 侧稳定输出：

- `run_id`
- `target_id`
- `dataset_id`
- `case_id`
- `verdict`
- `error_code`
- `latency_ms`
- `debug`
- `target_meta_json`

业务 target 不需要实现这个 schema。target 只需要返回 eval/chat response；`EvalRunResult` 是 harness 落库和 report 的产物。

## 4. 不应该共享的内容

以下内容不应该跨项目共享或互相依赖：

- 具体 Agent 实现，例如 `TravelAgent`、`RagStreamChatService` 或某个 target 的 controller/service 类。
- prompt，包括 plan prompt、rewrite prompt、金融分析 prompt、guard prompt。
- tool registry，包括工具发现、白名单、schema 注册、MCP client、mock tool 实现。
- RAG 策略，包括 hybrid、rerank、threshold、chunk id、召回数量、embedding provider。
- memory，包括 Redis ChatMemory、用户画像、长期记忆注入策略。
- SSE 拼接，包括 event 顺序、heartbeat、done/error 包装、首帧 meta、token chunk 合并。

这些属于单项目 runtime 实现。跨项目只能约定它们输出到 eval response 后的稳定观测语义。

## 5. 当前已统一字段

当前两个 target 与 `vagent-eval` 已基本统一的最小 response 字段：

```json
{
  "answer": "string",
  "behavior": "answer|clarify|deny|tool",
  "latency_ms": 123,
  "capabilities": {},
  "meta": {
    "mode": "EVAL"
  }
}
```

已基本统一的可选顶层字段：

- `sources`
- `retrieval_hits`
- `error_code`
- `tool`

已基本统一的 `capabilities` 分区：

- `capabilities.retrieval`
- `capabilities.tools`
- `capabilities.streaming`
- `capabilities.guardrails`

已基本统一的常用 `meta` 字段：

- `mode`
- `retrieve_hit_count`
- `low_confidence`
- `low_confidence_reasons`
- `retrieval_hit_id_hashes`
- `retrieval_candidate_limit_n`
- `retrieval_candidate_total`
- `canonical_hit_id_scheme`
- `eval_safety_rule_id`

`vagent-eval` 当前最小 contract validator 只强制顶层 `answer/behavior/latency_ms/capabilities/meta`，并要求 `meta.mode` 存在。其他字段主要由具体 eval rule 消费。

## 6. 当前不一致字段

当前不一致主要集中在 target-specific meta 和增强观测字段。

### sources 与 retrieval_hits

`travel-ai-planner`：

- `sources[]` 支持 `id/title/snippet/score`。
- `retrieval_hits[]` 支持 `id/title/score`。

`Vagent`：

- `sources[]` 支持 `id/title/snippet`。
- `retrieval_hits[]` 支持 `id/score`。

建议：`id` 保持必需语义；`title`、`snippet`、`score` 继续 optional。

### tool meta

`travel-ai-planner` 更偏工具治理过程：

- `tool_calls_count`
- `tool_outcome`
- `tool_latency_ms`
- `tool_disabled_by_circuit_breaker`
- `tool_rate_limited`
- `tool_output_truncated`

`Vagent` 更偏工具 schema 与 registry：

- `tool_error_code`
- `tool_schema_violations`
- `tool_version`
- `tool_schema_hash`
- `tool_arg_schema_validated`
- `tool_result_schema_required`
- `tool_result_schema_validated`

建议：顶层 `tool.required/used/succeeded/name/outcome/latency_ms` 作为 eval 判定口径；各 target 的 `meta.tool_*` 保持 optional。

### policy_events

`travel-ai-planner` eval response 当前已有 `meta.policy_events[]`，但事件 DTO 只包含：

- `policy_type`
- `stage`
- `behavior`
- `rule_id`
- `error_code`

主线 SSE 的 `PolicyEvent` 已支持 `attrs`，finance guard attrs 当前存在于主线 SSE 语义里，但 eval DTO 尚未承载。

`Vagent` 当前没有统一 `meta.policy_events[]`。

### workflow_id

`travel-ai-planner` finance guard 主线事件已经有 `workflow_id=market_data_explain` 的语义，但 eval response 当前没有标准 `meta.workflow_id`。

`Vagent` 当前没有 `workflow_id`。

`vagent-eval` 当前不会强校验或理解 `workflow_id`。

## 7. workflow_id / policy_events / attrs 推荐标准

推荐把 workflow 与 policy 扩展统一放在 `meta` 下：

```json
{
  "meta": {
    "workflow_id": "market_data_explain",
    "policy_events": [
      {
        "policy_type": "finance_guard",
        "stage": "tool",
        "behavior": "answer",
        "rule_id": "market_data_mock_non_realtime",
        "error_code": null,
        "attrs": {
          "workflow_id": "market_data_explain",
          "connector": "market_data",
          "mock_mode": "true",
          "freshness": "mock_non_realtime",
          "tradable": "false",
          "disclosure_required": "true",
          "investment_advice_allowed": "false"
        }
      }
    ]
  }
}
```

字段语义：

- `meta.workflow_id`：本次 response 的主 workflow 标识。适合 report、compare、debug 快速聚合。
- `meta.policy_events[]`：本次 response 中发生过的策略事件序列。
- `meta.policy_events[].attrs`：某条策略事件的扩展属性。值建议保持 string，便于跨语言、落库、脱敏和 compare。
- `attrs.workflow_id`：当某个 policy event 需要绑定 workflow 时可重复写入，便于只看事件数组也能完成归因。

推荐约束：

- `workflow_id` optional。
- `policy_events` optional。
- `policy_events[].attrs` optional。
- `attrs` 不放 query 原文、answer 原文、token、密钥、用户隐私。
- 金融领域 attrs 只放合规和数据治理归因，不放可识别用户内容。

## 8. 新增字段为什么放在 meta 下并保持 optional

新增字段应放在 `meta` 下，原因是：

- `meta` 是 target-specific observability 的扩展区，适合承载 workflow、policy trace、工具治理、低置信、检索统计等非最小契约字段。
- 顶层字段应保持小而稳定，避免每个业务方向都推动顶层 contract 膨胀。
- `vagent-eval` 已经会保存 target 返回的 `meta` 快照，后续 report 和 compare 可以通过 `meta-trace-keys` 选择性展示。
- `meta` 下新增字段不会破坏旧 target，也不会要求三个项目同步升级。
- optional 可以允许单项目先实施，另一个 target 暂时缺省。
- optional 可以避免把 finance-agent 的领域语义强加给 Vagent。

新增字段不应成为最小必填 contract。只有当 eval rule 真正依赖某字段判定时，才应在对应 rule 中做条件校验。

## 9. 后续分阶段计划

### Phase 1: travel-ai-planner eval DTO 支持 policy_events.attrs

范围只在 `travel-ai-planner` 内：

- 扩展 eval response 的 `meta.policy_events[]` 事件结构，增加 optional `attrs`。
- 将主线 finance guard 已有 attrs 映射到 eval response。
- 增加 `meta.workflow_id`，初始用于 `market_data_explain`。
- 保持原有 `policy_type/stage/behavior/rule_id/error_code` 不变。

该阶段不要求 Vagent 和 `vagent-eval` 同步改动。

### Phase 2: vagent-eval 宽松校验 workflow_id / policy_events

范围只在 `vagent-eval` 内：

- `meta.workflow_id` 若存在，应为 string。
- `meta.policy_events` 若存在，应为 array。
- `policy_events[].attrs` 若存在，应为 object。
- `attrs` value 建议允许 string，必要时兼容 boolean/number 并在落库或 compare 时转为字符串展示。

这些校验应是宽松校验，不应把 `workflow_id` 或 `policy_events` 变成必填字段。

### Phase 3: Vagent 可选支持空 policy_events

范围只在 `Vagent` 内：

- 可以选择不返回 `policy_events`。
- 也可以返回 `meta.policy_events: []` 表示本轮没有跨项目 policy event。
- 如未来 Vagent 有自己的 safety 或 RAG policy trace，可按同一结构追加。

该阶段不要求 Vagent 实现 finance guard。

### Phase 4: 未来 MainSseChatAdapter 覆盖真实主线 SSE

长期可以在 target 内增加 `MainSseChatAdapter`，用于把真实主线 SSE 事件转换为 eval 可消费的结构化结果。

目标：

- 让 eval 能覆盖真实主线 SSE，而不只覆盖 eval endpoint 的同步适配层。
- 复用 stage、policy event、tool outcome、error code 的共享语义。
- 保持 adapter 是 target 内部实现，不进入跨项目公共依赖。

非目标：

- 不要求 `vagent-eval` 理解每个 target 的 SSE 私有格式。
- 不要求所有 target 使用同一个 SSE 拼接实现。
- 不引入复杂新架构替代现有 eval endpoint。

## 10. 明确原则

核心原则：

```text
跨项目设计，单项目实施。
```

具体含义：

- 跨项目只设计稳定协议、字段语义、判定规则和观测边界。
- 单项目按自身代码结构实施，不复制其他项目的 Agent、prompt、tool registry、RAG、memory 或 SSE 实现。
- 新字段先 optional，先服务观测和 compare，再根据明确 eval rule 决定是否条件校验。
- 不创建复杂新架构，不要求三个项目同步改。
- `vagent-eval` 面向 target response，而不是面向 target 内部代码。
- 业务 target 面向产品 runtime，同时提供一个薄的 eval adapter。

面试讲解时可以概括为：

> 三个项目共享的是评测协议和可观测语义，不共享业务实现。`travel-ai-planner` 和 `Vagent` 都是被测 target，`vagent-eval` 是独立 harness。新增 workflow 和 policy attrs 放在 `meta` 下并保持 optional，是为了允许金融场景先演进，同时不破坏其他 target 和旧回归。
