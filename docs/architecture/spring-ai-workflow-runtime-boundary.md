# Spring AI / Workflow Runtime Boundary

本文定义 Spring AI Runtime、Custom Workflow Runtime、`TravelAgent` / `FinancialAnalystAgent`、Eval Runtime 之间的职责边界。目标是让 Finance Agent 后续 R3/R4 演进保持清晰：复用 Spring AI 的模型、流式、advisor、memory、vector store 和工具基础设施，同时在其上实现轻量、可评测、可治理的业务 workflow runtime。

## Boundary Summary

Spring AI 负责模型和 AI 基础设施。

Custom Workflow Runtime 负责业务阶段编排、结构化节点结果和 trace。

`TravelAgent` 当前是 legacy-compatible implementation，仍承载具体 stage 业务逻辑；R2 后 runtime 已开始接管阶段调度。

Eval Runtime 负责离线/评测入口、Eval Contract V1 响应契约和跨 target 可比性。

这些层不应互相替代：

- Spring AI 不是业务 workflow runtime。
- Custom Workflow Runtime 不是 LLM SDK。
- `TravelAgent` 不应长期继续膨胀成所有抽象的集合点。
- Eval Runtime 不应反向污染主线 SSE 协议。

## Spring AI Responsibilities

Spring AI 是底层 AI 应用基础设施层，负责：

- `ChatClient`
  - 构造 prompt。
  - 调用模型。
  - 暴露 blocking / streaming API。

- LLM provider abstraction
  - 屏蔽具体 provider 细节。
  - 当前项目通过 Spring AI Alibaba / DashScope options 接入模型能力。
  - 后续切换 provider 时，业务 workflow 不应直接依赖 provider SDK。

- Streaming
  - 提供 `ChatClient.stream()` 和 token/content stream。
  - 处理模型输出流的抽象。
  - 项目主线在其上封装 SSE，但不重新实现底层模型 streaming runtime。

- Advisors
  - 提供 advisor chain。
  - 当前使用 memory advisor 和 logger advisor。
  - advisor 的执行顺序、注入点和模型调用增强应继续交给 Spring AI。

- Memory advisor
  - 普通 LLM 对话的 user/assistant memory 写入由 advisor 处理。
  - 项目只在 empty-hit clarify 这种绕过 LLM 的路径手动补齐 memory。

- Vector store integration
  - 提供 vector store 抽象。
  - 当前自定义 `PgVectorStore` 承接项目 metadata/user_id 过滤需求，但仍处于 Spring AI vector store 生态边界内。

- Tool calling infrastructure
  - Spring AI 提供工具调用相关基础能力。
  - 项目当前在业务层额外实现 tool governance，例如 timeout、rate limit、circuit breaker、ToolResult 归因。

Spring AI 层不负责：

- Finance workflow 的阶段顺序。
- `PLAN / RETRIEVE / TOOL / GUARD` 的业务决策。
- Eval Contract V1 trace 字段。
- 金融合规 guard。
- target-specific policy events。

## Custom Workflow Runtime Responsibilities

Custom Workflow Runtime 是项目自己的轻量业务 workflow runtime，运行在 Spring AI 之上。

它负责：

- `PLAN / RETRIEVE / TOOL / GUARD` workflow orchestration
  - R2 后通过 `LinearWorkflowRuntime` 薄包装同步阶段。
  - `WRITE` / SSE streaming 仍不纳入 runtime。

- `WorkflowNode`
  - 描述一个可执行业务阶段。
  - R2 中对应 `PlanStageNode`、`RetrieveStageNode`、`ToolStageNode`、`GuardStageNode`。

- `NodeResult` / `NodeStatus`
  - 结构化表达节点执行结果。
  - 支持 `SUCCESS`、`SKIPPED`、`FAILED`、`TIMEOUT`。
  - 支持 `continueWorkflow` 控制线性流程停止。

- `StageTrace` / `ToolTrace`
  - `StageTrace` 描述阶段执行结果和耗时。
  - `ToolTrace` 描述工具调用结果。
  - R2 只使用 stage trace 映射主线 stage event；R3 再做更完整 trace alignment。

- `workflow_id` / `workflow_family`
  - 作为 workflow 级 metadata。
  - 用于把 `market_data_explain`、未来 company snapshot 等业务 workflow 与普通聊天区分开。

- `policy_events`
  - Runtime 可以收集或承载 policy events。
  - 但 R2 不重复生成 policy events。
  - 当前 `tool_stage` 仍由 tool stage 业务逻辑生成，`rag_gate` / `finance_guard` 仍由 guard stage 业务逻辑生成。

- Eval Contract V1 trace alignment
  - Runtime trace 是未来对齐 `meta.stage_trace[]`、`meta.tool_trace[]`、`meta.policy_events[]` 的内部语义来源。
  - R2 不改 eval 输出，R3/R4 再逐步映射。

Custom Workflow Runtime 不负责：

- 直接调用 provider SDK。
- 实现底层 token streaming。
- 实现 advisor chain。
- 实现 embedding runtime。
- 取代 Spring AI vector store 抽象。
- 接管 Redis memory。
- 接管 Controller 路由。
- 引入 DAG / MQ / Temporal 风格大 runtime。

## TravelAgent / FinancialAnalystAgent Role

`FinancialAnalystAgent` 是当前金融分析语义接口。

`TravelAgent` 是 legacy-compatible implementation：

- 历史类名保留，避免大规模 package/class rename。
- 当前仍是 `FinancialAnalystAgent` 的主要实现。
- 当前仍承载具体 stage 业务逻辑：
  - `stagePlan`
  - `stageRetrieve`
  - `stageTool`
  - `stageGuard`
  - `stageWrite`
  - prompt merge
  - empty-hit guard
  - market data finance guard
  - SSE event assembly

R2 后：

- Runtime 已开始接管 `PLAN / RETRIEVE / TOOL / GUARD` 的阶段调度。
- `TravelAgent` 中的阶段方法仍是业务真源。
- `MainAgentTurnContext` 仍是主线 turn state 真源。
- `WorkflowContext` 只是 runtime 执行记录容器。

长期方向：

- `TravelAgent` 不需要被立即删除。
- 它应逐步降级为 workflow adapter / facade。
- 具体业务节点可以逐步外移，但应以小步迁移、测试覆盖和 feature flag 灰度为前提。
- 不进行一次性“大拆类”或 package rename。

## Eval Runtime Responsibilities

Eval Runtime 指 `/api/v1/eval/chat` 及其相关 service、DTO、validator/report 生态。

它负责：

- 非流式评测入口。
- Eval Contract V1 response。
- `meta.workflow_id`、`meta.workflow_version`、`meta.workflow_family`。
- `meta.policy_events[]` 和 `attrs`。
- `meta.stage_trace[]`、`meta.tool_trace[]`、`meta.evidence_summary` 的承载和后续对齐。
- 跨 target 评测时保持 optional、宽松、向后兼容。

Eval Runtime 不负责：

- 主线 SSE streaming。
- Redis ChatMemory。
- Controller `/analysis/chat` 行为。
- Spring AI provider 调用链。
- 生产 workflow 调度。

未来 R4 可以让 Eval Runtime 消费 Custom Workflow Runtime 的 trace，但不应让 eval DTO 成为主线内部模型。

## What We Should Not Hand-Roll

项目不应该自己手搓以下部分：

- LLM SDK
  - 不直接封装 provider HTTP API 作为主线模型调用基础。
  - 使用 Spring AI 的 provider abstraction。

- 底层 stream runtime
  - 不重新实现 token stream 协议。
  - 不替代 `ChatClient.stream()`。

- advisor chain
  - 不手写 memory advisor / logger advisor 的通用执行框架。
  - 业务层只决定使用哪些 advisor。

- embedding runtime
  - 不重新实现 embedding provider runtime。
  - 继续复用 Spring AI embedding/vector store 生态。

- provider abstraction
  - 不把 DashScope/OpenAI/其他 provider 的差异散落到业务 workflow。
  - provider 差异应留在 Spring AI 配置和 options 层。

Custom Workflow Runtime 的价值不在于替代 Spring AI，而在于补齐项目自己的业务阶段、治理事件和评测 trace。

## Why Not LangGraph Directly

当前没有直接使用 LangGraph，原因是边界和目标不同：

- Java / Spring 生态
  - 当前项目主体是 Spring Boot 3、Spring Security、Spring AI、JDBC、Redis、pgvector。
  - 使用 Spring 原生配置、测试和部署链路更直接。

- Spring AI integration
  - 当前已使用 Spring AI `ChatClient`、advisor、memory、vector store 和工具相关基础设施。
  - 直接引入另一个通用 agent framework 会增加运行时边界和调试成本。

- 金融业务治理
  - 项目需要 finance guard、mock market data disclosure、tool governance、policy event、citation membership 等业务可观测语义。
  - 这些语义需要贴近现有 Spring Boot 后端和 Eval Contract。

- Eval Contract V1 对齐
  - 当前重点是把 workflow、policy、tool、stage trace 变成可评测、可比较的 target response。
  - 这要求 runtime 输出与 eval harness 的字段稳定对齐。

- 当前目标是轻量业务 workflow runtime
  - 不是通用 agent framework。
  - 不是 DAG runtime。
  - 不是多 agent 编排平台。
  - 不是 MQ/Temporal 风格长期任务系统。

因此，当前选择是在 Spring AI 上方构建项目内轻量 workflow runtime，而不是引入一个独立通用 agent framework。

## Relationship to Spring AI Agentic Patterns

当前 Custom Workflow Runtime 更接近 deterministic workflow orchestration：

- 阶段固定。
- 顺序明确。
- 每个节点结果结构化。
- guard 和 policy event 可追踪。
- 目标是可测试、可回归、可评测。

这与 Spring AI 官方 agentic / effective agents / workflow patterns 并不冲突。

关系可以理解为：

- Spring AI 提供 agentic 应用所需的模型、工具、advisor、memory、vector store 基础能力。
- 项目自己的 runtime 在 Spring AI 之上工作。
- 业务 runtime 负责金融分析场景中的阶段边界、治理事件和 Eval Contract 对齐。
- 如果未来 Spring AI 提供更成熟的 workflow/agent pattern API，可以评估将节点执行接口适配过去，但不应在当前阶段打断已有主线。

## Future Evolution

R3 trace alignment：

- 将 runtime `StageTrace` 与主线 `StageEvent`、Eval Contract V1 `meta.stage_trace[]` 对齐。
- 梳理 `ToolResult` 到 `ToolTrace` 的稳定映射。
- 保持 policy event 不重复生成。

R4 eval/runtime convergence：

- 让 eval endpoint 可以消费与主线一致的 runtime trace 语义。
- 保持所有新字段 optional。
- 不破坏旧 target、旧 dataset、旧 report。

Future async / multi-agent possibility：

- 在 runtime trace、policy event、tool trace 稳定后，再评估 async job 或 multi-agent。
- 该方向只作为后续可能性，不在 R2/R3 中引入。
- 不提前引入 DAG、MQ、Temporal 或通用 agent framework 大重构。

## Non-Goals

当前阶段明确不做：

- 重写 `stageWrite(...)`。
- 接管 SSE streaming。
- 改 Redis memory。
- 改 Controller route。
- 改 DB / migration。
- 改 Eval Contract DTO 字段。
- 替代 Spring AI。
- 引入 LangGraph。
- 引入 DAG/MQ/Temporal。
- 一次性拆除 `TravelAgent`。
