# Agent Workflow Runtime Boundaries

本文定义 Finance Agent 主线、Workflow Runtime、Eval Contract、SSE、Redis memory、工具治理和持久化之间的职责边界。目标是让后续 Runtime R2/R3/R4 演进有清晰约束，避免把主线流式体验、评测契约和长期 runtime 抽象混在一起。

## 核心原则

- `/analysis/**` 是主推金融分析 API。
- `/finance/**` 是金融语义 alias。
- `/travel/**` 是 legacy-compatible endpoint，不建议新代码继续使用。
- `TravelAgent` 是历史命名保留的主线实现，当前服务 Finance Analyst workflow。
- Workflow Runtime 先承担“阶段调度 + trace 记录”，不接管 SSE streaming。
- Eval Contract V1 是对外评测响应契约，不应反向驱动主线 SSE 行为。
- Redis memory、DB schema、Controller route 都不是 Runtime R2/R3 的改造目标。

## 当前主线边界

`AnalysisController` / legacy `TravelController` 只负责 HTTP/SSE 入口校验和调用 Agent。

`TravelAgent` 当前仍是主线业务真源，负责：

- `PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE` 线性流程。
- RAG query rewrite、pgvector 检索、citation block。
- tool 选择、限流、熔断、timeout、ToolResult 归因。
- empty-hit guard、finance guard、policy event。
- prompt 组装。
- `stageWrite(...)` 的 ChatClient streaming。
- SSE event 拼接。
- Redis memory 的正常流式写入和 empty-hit 手动写入。

Runtime 不能在 R2 阶段替代这些业务状态，只能薄包装同步阶段。

## Workflow Runtime 边界

Runtime R1/R2 的职责：

- 以 `WorkflowTask` 表达一次 workflow 输入。
- 以 `WorkflowContext` 保存 runtime 自己的 attrs 和 trace。
- 以 `WorkflowNode` 包装阶段节点。
- 以 `NodeResult` / `NodeStatus` 表达节点结果。
- 以 `StageTrace` 记录阶段执行结果。
- 以 `LinearWorkflowRuntime` 顺序执行节点。

Runtime R2 可以包裹：

- `PLAN`
- `RETRIEVE`
- `TOOL`
- `GUARD`

Runtime R2 不接管：

- `WRITE`
- `stageWrite(...)`
- `ChatClient.stream()`
- SSE heartbeat / done / error
- Redis memory
- Controller route
- DB / migration
- Eval Contract DTO
- MQ / async job
- DAG / Multi-Agent

## MainAgentTurnContext 与 WorkflowContext

`MainAgentTurnContext` 是主线业务状态真源，包含 plan、retrieval、tool、guard、prompt、SSE、policy、memory 防重等状态。

`WorkflowContext` 是 runtime 执行记录容器，包含 task、runtime attrs、stage trace、tool trace、policy event 列表。

边界约束：

- R2 不用 `WorkflowContext` 替换 `MainAgentTurnContext`。
- 节点可以闭包引用 `MainAgentTurnContext` 并调用现有阶段方法。
- `WorkflowContext` 的 trace 可以映射为主线 `StageEvent`。
- 业务字段仍写回 `MainAgentTurnContext`。

## SSE 边界

SSE 是用户体验和传输层，不是 Runtime R2 的职责。

现有 SSE 输出包括：

- `event: plan_parse`
- `event: stage`
- `event: policy`
- citation 首包
-正文 data token
- heartbeat comment
- `event: done`
- `event: error`

Runtime 可以提供 stage trace，但不直接写 SSE。主线仍由 `TravelAgent#chat(...)` 统一拼接 SSE，避免事件顺序漂移。

## Redis Memory 边界

Redis memory 有两条路径：

- 普通 LLM 流式回答：由 ChatClient memory advisor 写入。
- empty-hit clarify：绕过 ChatClient，由 `appendTurnToMemory(...)` 手动写入，并用防重复标记保护。

Runtime 不应调用 Redis memory，也不应包裹 `stageWrite(...)`，否则容易导致重复订阅和重复写入。

## Policy Event 边界

Policy event 当前由业务阶段生成：

- `tool_stage`：由 `stageTool(...)` 生成。
- `rag_gate`：由 `stageGuard(...)` 生成。
- `finance_guard`：由 `stageGuard(...)` 的 market data guard 生成。

Runtime R2 不生成 policy event，只收集或保留现有事件。后续 R3/R4 可以考虑将 runtime trace 映射到 Eval Contract，但不能重复产生 policy event。

## Eval Contract V1 边界

Eval Contract V1 字段包括：

- `meta.workflow_id`
- `meta.workflow_version`
- `meta.workflow_family`
- `meta.policy_events[]`
- `meta.policy_events[].attrs`
- `meta.stage_trace[]`
- `meta.tool_trace[]`
- `meta.evidence_summary`

边界约束：

- Eval DTO 是外部 JSON 契约。
- Runtime trace 是内部执行语义。
- R3 之后可以建立 Runtime trace -> Eval DTO 的映射。
- R2 不填充 eval `stage_trace/tool_trace`。
- R2 不改变 `/api/v1/eval/chat` 行为。

## Tool Trace 边界

Tool execution 当前由 `stageTool(...)` 和 `ToolExecutor` 负责。

R2 不生成 `ToolTrace`。原因：

- ToolResult 与主线 policy event 已经稳定。
- ToolTrace 涉及 connector、required、used、succeeded、outcome、latency、input/output ref 等字段，需要 R3 单独对齐。
- 提前在 R2 生成 ToolTrace 容易造成 tool policy 和 eval trace 双重归因不一致。

## Feature Flag 边界

Runtime 接入必须通过 feature flag 控制：

```yaml
app:
  agent:
    workflow-runtime:
      enabled: false
```

要求：

- 默认关闭。
- 关闭时走旧路径。
- 开启时只切换 `PLAN/RETRIEVE/TOOL/GUARD` 调度方式。
- 出现问题时可通过配置立即回滚。

## 后续演进

R2：薄包装 `PLAN/RETRIEVE/TOOL/GUARD`，不改 WRITE/SSE。

R3：从 runtime `StageTrace` / tool execution result 生成主线可观测 trace。

R4：将 runtime trace 映射到 Eval Contract V1 的 `stage_trace/tool_trace`。

R5：评估 async job、runtime event store、multi-agent，但必须在 SSE/Redis/DB 边界稳定后再做。

## 禁止事项

在 Runtime R2/R3 阶段，不应做以下事情：

- 重命名 `TravelAgent` 或 package。
- 删除 `/travel/**` legacy route。
- 改 Controller route。
- 改 DB schema 或 migration。
- 改 Redis key。
- 接管 `stageWrite(...)`。
- 包裹 `ChatClient.stream()`。
- 引入 MQ。
- 引入 DAG。
- 引入 Multi-Agent。
- 让 Eval Contract DTO 反向污染主线 runtime 模型。

## 回滚边界

Runtime R2/R3 应保持可独立回滚：

- 首选关闭 `app.agent.workflow-runtime.enabled`。
- 若需要代码回滚，删除 runtime 接线即可，保留 R1 model 不影响业务。
- 不需要 DB 回滚。
- 不需要 Redis 清理。
- 不影响 `/analysis/**`、`/finance/**`、legacy `/travel/**` 路由。
- 不影响 eval response 旧字段兼容。
