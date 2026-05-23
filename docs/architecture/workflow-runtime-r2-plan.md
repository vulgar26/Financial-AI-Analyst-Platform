# Workflow Runtime R2 Plan

本文说明 Agent Workflow Runtime 下一阶段如何以“薄包装”方式接入当前 Finance Agent 主线。R2 只包裹 `TravelAgent` 中已经存在的 `PLAN / RETRIEVE / TOOL / GUARD` 阶段，不接管 `WRITE`、SSE、Redis memory、Controller 路由、DB、MQ、DAG 或 Multi-Agent。

## R1 已完成

R1 已新增一组独立 runtime 抽象，尚未接入业务链路：

- `com.travel.ai.runtime.LinearWorkflowRuntime`
- `com.travel.ai.runtime.model.WorkflowTask`
- `com.travel.ai.runtime.model.WorkflowContext`
- `com.travel.ai.runtime.model.NodeResult`
- `com.travel.ai.runtime.model.NodeStatus`
- `com.travel.ai.runtime.node.WorkflowNode`
- `com.travel.ai.runtime.trace.StageTrace`
- `com.travel.ai.runtime.trace.ToolTrace`

R1 的定位是纯模型和纯单测：按顺序执行节点、计时、收集 `StageTrace`，在节点返回 `continueWorkflow=false` 或抛异常时停止后续节点。

## R2 目标

R2 的目标是把当前 `TravelAgent#runLinearStages(...)` 中的四个同步阶段改为 runtime 薄包装：

- `stagePlan(ctx)`
- `stageRetrieve(ctx)`
- `stageTool(ctx)`
- `stageGuard(ctx)`

R2 不改变阶段内部业务逻辑，不改变 `TravelAgent#chat(...)` 的 SSE 拼接方式，不改变 `stageWrite(...)`，也不改变 eval response 契约。

推荐策略是保留旧路径，并通过 feature flag 增加一条 runtime 包裹路径：

- 默认：继续执行现有 `runLinearStages(ctx)` 逻辑。
- 开启：执行 `runLinearStagesWithRuntime(ctx)`，内部仍调用现有 stage 方法。

## WRITE / SSE 暂不纳入 Runtime

`WRITE` 阶段暂不纳入 runtime，原因是它不是普通同步节点：

- `stageWrite(...)` 返回 `Flux<String>`，生命周期由 SSE 订阅驱动。
- 当前实现依赖 `share()` 避免 `ChatClient` 流被重复订阅。
- empty-hit clarify 分支依赖 `cache()` 和 `appendTurnToMemory(...)` 的同步写入，避免 Redis memory 重复追加。
- `chat(...)` 中还负责合并 `plan_parse`、`stage`、`policy`、citation、token、heartbeat、done/error。
- 总超时、LLM 子流超时、fallback 文本和 `SseControlEvent` 都在 SSE 层处理。

如果 R2 贸然把 `WRITE` 放入 runtime，最主要风险是重复订阅 LLM 流、重复写 Redis memory、改变 SSE 事件顺序。因此 R2 明确不接管 `stageWrite(...)`。

## MainAgentTurnContext 与 WorkflowContext

R2 不替换 `MainAgentTurnContext`。

当前 `MainAgentTurnContext` 是 `TravelAgent` 内部的 turn state，承载：

- conversation/user/request 基础信息
- PLAN 输出和 plan parse 元数据
- RAG 查询、检索结果、promptBase、citationBlock
- toolPreface、finalPromptForLlm
- guard 决策、empty-hit clarify 状态
- `stageEvents`
- `policyEvents`
- Redis memory 防重复写入状态
- LLM stream error code

`WorkflowContext` 在 R2 中只作为 runtime 执行容器，用来保存：

- `WorkflowTask`
- runtime attrs
- `StageTrace`
- `ToolTrace`
- `PolicyEvent`

两者关系应保持单向、轻量：

- `MainAgentTurnContext` 仍是业务真源。
- `WorkflowContext` 是 runtime 包装层的执行记录。
- 节点执行时持有或闭包引用 `MainAgentTurnContext`，调用现有 stage 方法。
- 执行结束后，只把 runtime `StageTrace` 映射回 `ctx.stageEvents`，不要把 `WorkflowContext` 变成新的业务状态中心。

## 薄包装节点设计

R2 可以在 `TravelAgent` 内部定义私有节点适配器，也可以放在 package-private 内部类中。为了最小改动和避免扩大 API，优先建议先作为 `TravelAgent` 的私有内部类或私有工厂方法存在。

### PlanStageNode

职责：

- `name()` 返回 `PLAN`。
- `execute(runtimeCtx)` 内部调用现有 `stagePlan(mainCtx)`。
- 调用后解析 `ctx.planJson`，执行现有 `PlanPhysicalStagePolicy.resolve(...)`。
- 将物理阶段 flags 暂存在 runtime attrs 或局部变量中，供后续节点判断是否 skip。

注意：

- 不改变 `stagePlan(...)` 内部 parse/repair/fallback 逻辑。
- 不重复写 `PlanParseEvent`。
- 不改变 `ctx.planDraftSource`、`ctx.planParseOutcome`、`ctx.planParseAttempts`、`ctx.planParseResolved`。

### RetrieveStageNode

职责：

- `name()` 返回 `RETRIEVE`。
- 如果 PLAN 决定跳过 RETRIEVE，调用现有 `applyRetrieveSkipped(mainCtx)`，返回 `NodeResult` status=`SKIPPED`。
- 如果需要执行，调用现有 `stageRetrieve(mainCtx)`，返回 `SUCCESS`。

注意：

- 不改变 query rewrite、vector search、user filter、dedupe、citationBlock 逻辑。
- 不额外追加 `StageEvent.skip(...)`，避免和现有 skip 方法重复生成事件；R2 需要先明确事件生成归属，见下文。

### ToolStageNode

职责：

- `name()` 返回 `TOOL`。
- 如果 PLAN 决定跳过 TOOL，调用现有 `applyToolSkipped(mainCtx)`，返回 `SKIPPED`。
- 如果需要执行，调用现有 `stageTool(mainCtx)`，返回 `SUCCESS`。

注意：

- 不改变 `selectTool(...)`、`executeGovernedTool(...)`、circuit breaker、rate limit、ToolResult 逻辑。
- 不重复生成 `tool_stage` policy event。
- R2 暂不从 runtime 直接生成 `ToolTrace`，避免和后续 R3 的 trace 对齐工作混在一起。

### GuardStageNode

职责：

- `name()` 返回 `GUARD`。
- 如果 PLAN 决定跳过 GUARD，保留当前 skip 行为，返回 `SKIPPED`。
- 如果需要执行，调用现有 `stageGuard(mainCtx)`，返回 `SUCCESS`。

注意：

- 不改变 empty-hit guard。
- 不改变 `rag_gate` policy event。
- 不改变 `finance_guard` policy event。
- 不改变 `market_data_explain` 行为。

## 私有方法可见性

最小方案是 runtime 包裹逻辑先写在 `TravelAgent` 内部，使节点能够直接调用现有私有方法：

- `stagePlan`
- `stageRetrieve`
- `stageTool`
- `stageGuard`
- `applyRetrieveSkipped`
- `applyToolSkipped`
- `logStageBoundary`

如果后续需要把节点类移到独立文件，不建议直接把这些方法改成 `public`。更稳妥的最小调整是：

- 改为 package-private，仅限 `com.travel.ai.agent` 包内适配器调用。
- 或新增极薄 package-private adapter，例如 `TravelAgentStageDelegate`，由 `TravelAgent` 构造并注入必要回调。

R2 不应引入大规模拆类，也不应把 `TravelAgent` 的内部状态暴露成公共 API。

## 避免重复生成 StageEvent / PolicyEvent

当前 `runLinearStages(...)` 会显式追加：

- `StageEvent.start(...)`
- `StageEvent.end(...)`
- `StageEvent.skip(...)`

同时 `stageTool(...)` 和 `stageGuard(...)` 会追加 policy events：

- `tool_stage`
- `rag_gate`
- `finance_guard`

R2 必须先定义唯一事件生成点：

- `PolicyEvent`：继续由现有 `stageTool(...)` / `stageGuard(...)` 生成，runtime 不生成 policy event。
- `StageEvent`：推荐由 runtime `StageTrace` 统一映射生成，旧路径继续按原逻辑生成。

因此 runtime 包裹路径中不应同时保留旧的 `ctx.stageEvents.add(StageEvent.start/end/skip)` 逻辑和新映射逻辑。否则 SSE 会出现重复 stage 事件。

最小做法：

- 保留旧 `runLinearStages(ctx)` 完全不动。
- 新增 `runLinearStagesWithRuntime(ctx)`，只在新路径中使用 runtime 生成 stage trace。
- 新路径调用的 stage 方法本身不要再负责 `StageEvent.start/end`，只负责业务状态和 `stageElapsedMs`。
- 对现有 `applyRetrieveSkipped(...)` / `applyToolSkipped(...)` 已经会追加 skip event 的情况，R2 需要拆出“只应用 skip 状态、不追加 StageEvent”的 helper，或在 runtime 路径避免再次映射 skip event。

## NodeResult / StageTrace 到 ctx.stageEvents 的映射

R2 的映射应保持现有 SSE wire 语义：

- `NodeStatus.SUCCESS` -> `StageEvent.start(stage)` + `StageEvent.end(stage, elapsedMs)`
- `NodeStatus.SKIPPED` -> `StageEvent.skip(stage, reason)`
- `NodeStatus.FAILED` -> 可映射为 `StageEvent` kind=`ERROR`，但 R2 更稳妥的是保持现有异常路径：抛出后由 `chat(...)` 返回 `event:error`
- `NodeStatus.TIMEOUT` -> 同 FAILED，R2 不新增节点级 timeout 行为

为了保持 SSE 顺序不变，新路径应生成与旧路径等价的顺序：

1. PLAN start
2. PLAN end
3. RETRIEVE start/end 或 RETRIEVE skip
4. TOOL start/end 或 TOOL skip
5. GUARD start/end 或 GUARD skip

`WRITE start/end` 仍由 `chat(...)` 和 `stageWrite(...)` 原逻辑生成。

## Feature Flag

建议新增配置项，但默认关闭：

```yaml
app:
  agent:
    workflow-runtime:
      enabled: false
```

行为：

- `false`：继续调用旧 `runLinearStages(ctx)`。
- `true`：调用 `runLinearStagesWithRuntime(ctx)`。

R2 不改变默认行为，便于灰度、对比和快速回滚。

如果不想在 R2 改 `application.yml`，也可以先在 `AppAgentProperties` 增加默认 false 字段，不写配置文件；但最终验收时应能通过配置打开。

## 验收标准

R2 开启 feature flag 后必须满足：

- SSE 事件顺序不变。
- SSE `plan_parse`、`stage`、`policy`、citation、token、heartbeat、done/error 不变。
- Redis memory 行为不变。
- empty-hit guard 行为不变，包括 clarify 文本和 Redis 手动写入次数。
- `market_data_explain` 行为不变，包括 mock disclosure、finance guard、工具治理结果。
- Eval Contract V1 不变，`/api/v1/eval/chat` 不受影响。
- 默认关闭 feature flag 时，行为与当前主线完全一致。
- 现有 Controller 路由和前端调用不变。

建议测试：

- feature flag off：现有 `TravelAgent` / Controller 测试不变。
- feature flag on：新增最小主线测试，验证 stage event 顺序与旧路径一致。
- empty-hit case：确认不重复写 Redis memory。
- market data case：确认 policy event 不重复，finance guard 仍只出现一次。

## 风险

主要风险：

- 重复 stage event：旧路径手写 `StageEvent` 与 runtime trace 映射同时生效。
- 重复 policy event：runtime 节点如果也生成 `tool_stage/rag_gate/finance_guard`，会和现有 stage 方法重复。
- skip 行为重复：`applyRetrieveSkipped(...)` / `applyToolSkipped(...)` 当前可能同时写业务状态和 skip event，runtime 再映射会重复。
- mutable ctx 依赖：`MainAgentTurnContext` 字段写入顺序很重要，节点包装不能改变阶段间状态传递。
- 异常路径变化：runtime 把异常转 `FAILED NodeResult`，但主线当前依赖抛异常进入 `chat(...)` 的 pipeline error 分支。R2 应保持现有异常语义。
- feature flag 配置错误：默认必须为 false，避免上线后自动切换路径。

## 回滚方案

R2 应独立可回滚：

- 关闭 `app.agent.workflow-runtime.enabled` 即回到旧路径。
- 如果需要代码回滚，只删除 `runLinearStagesWithRuntime(...)` 和节点适配器，保留 R1 runtime model 不影响业务。
- 不涉及 DB、Redis key、SSE 协议、Controller 路由、Eval Contract DTO，因此回滚不需要数据迁移。
- 不改 `stageWrite(...)`，避免回滚时处理 Reactor/SSE 订阅副作用。
