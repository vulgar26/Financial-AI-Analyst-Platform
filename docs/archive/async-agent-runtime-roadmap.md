# Async Agent Runtime Roadmap

## 项目定位

当前主链路以单轮 SSE 请求为中心，适合交互式问答和中等复杂度分析。金融分析、批量检索、报告生成、图表解析和多工具调用会带来更长耗时、更高并发和更复杂的失败恢复需求，因此需要长期演进出异步 Agent Runtime。

异步运行时的目标不是替换当前 SSE，而是把当前可观测的阶段语义扩展到后台任务：用户可以提交任务、订阅进度、恢复结果、查看工具轨迹和失败原因。

## 当前状态

当前已经具备异步运行时的基础组件：

- 固定阶段语义：`StageName`、`StageEvent`、`PolicyEvent`、`SseControlEvent`。
- SSE 流式响应：支持阶段事件、引用片段、心跳、done/error。
- Redis：可存储会话、短期状态、限流桶和 pending 数据。
- PostgreSQL：可作为任务、checkpoint、结果、审计记录的长期存储。
- Eval checkpoint：已有 `eval_conversation_checkpoint` 的断点恢复思路。
- Tool governance：已有 timeout、rate limit、circuit breaker、outcome、error_code。

当前限制：

- 主产品链路仍以单 HTTP/SSE 请求为执行边界。
- 长报告和批量分析没有独立 job 生命周期。
- 任务状态、阶段快照、部分结果、重试策略尚未产品化。
- 高并发下缺少队列、worker、backpressure 和任务优先级模型。

## 长期目标

目标架构：

```text
Client
  -> POST /analysis/jobs
  -> Job API writes analysis_job
  -> Queue / Redis Stream / Worker Pool
  -> Agent Runtime
     -> PLAN
     -> RETRIEVE
     -> TOOL
     -> GUARD
     -> WRITE / REPORT
  -> Job Event Store
  -> GET /analysis/jobs/{id}
  -> GET /analysis/jobs/{id}/events or SSE subscribe
```

核心能力：

- Job lifecycle：`PENDING`、`RUNNING`、`WAITING_TOOL`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`EXPIRED`。
- Event sourcing：阶段开始、阶段结束、policy 命中、tool outcome、partial report、error。
- Checkpoint：按阶段保存输入、输出摘要、工具结果和可恢复状态。
- Worker pool：控制并发、隔离不同任务类型、支持优先级和超时预算。
- Backpressure：队列长度、用户额度、全局熔断、降级策略。
- Result store：保存报告、引用、工具轨迹、数据时间、生成参数。
- SSE bridge：前端可订阅 job events，复用当前 SSE 事件语义。
- Cache layer：工具响应、检索结果、报告草稿、任务状态分层缓存。

## V1

目标：建立最小可用异步任务模型。

- 新增 `analysis_job` 设计文档，定义 job id、owner、status、type、created_at、updated_at、error_code。
- 将当前 stage event / policy event 语义映射到 job event。
- 支持同步执行和异步记录并存：先不引入复杂队列，允许单机 worker 处理。
- 提供任务查询模型：job status、stage order、latest event、result summary。
- 明确 timeout budget：total timeout、tool timeout、LLM stream timeout、job max age。
- 将长报告生成、批量财报摘要作为首批异步候选任务。

## V2

目标：支持高并发和可恢复执行。

- 引入队列实现，可选 Redis Stream、PostgreSQL SKIP LOCKED 或外部 MQ。
- 建立 worker pool，按任务类型配置并发：chat、report、ingestion、eval。
- 实现 checkpoint persistence，支持 worker 崩溃后的任务恢复或安全失败。
- 支持 cancellation：用户取消、系统超时、管理员终止。
- 支持 retry policy：工具瞬时失败、provider timeout、网络错误可有限重试。
- 引入 job event stream，前端通过 SSE 订阅后台任务进度。
- 对热门工具响应和市场数据增加 TTL cache，降低 provider 压力。

## V3

目标：形成生产级 Agent Runtime。

- 多实例 worker 水平扩展，支持租户级 quota 和任务优先级。
- 任务 DAG 化仅限后台 runtime，主对话仍保留清晰的线性阶段语义。
- 引入 dead letter queue 和人工复核队列。
- 建立 runtime dashboard：队列深度、吞吐、失败率、P95/P99 latency、tool outcome 分桶。
- 支持任务模板：公司深度分析、行业周报、新闻事件追踪、财报对比。
- 支持结果版本化和报告再生成，保留输入、工具、模型和 policy 版本。

## 风险与边界

- 异步化不应破坏当前 SSE 聊天体验，短交互仍应保持低延迟。
- 不应在 V1 过早引入复杂分布式调度，先用清晰 job model 固化契约。
- 长任务必须有明确超时、取消和结果保留策略，避免无限占用资源。
- 工具输出必须作为数据处理，不能被当作系统指令。
- 后台任务需要更严格的用户隔离、权限校验和审计记录。
- 金融场景下，异步报告仍必须经过合规 guard 和引用校验。

