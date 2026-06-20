# ARCHITECTURE — 当前真实架构

> 以当前源码和 `application.yml` 为准。历史阶段记录已删，需要时查 git。
> 命名提示：`TravelAgent`、`com.travel.ai`、`/travel/**` 是 legacy 兼容命名（项目早期是旅行 AI），不是 bug，改名是独立的未来任务。

## 0. API 命名

- `/analysis/**` — 首选接口面。
- `/finance/**` — 金融语义 alias。
- `/travel/**` — legacy 兼容端点，新代码勿用。

## 1. 请求链路

核心是**固定线性编排下的可解释 RAG + SSE**：

```
客户端 (SSE，经鉴权与限流)
  → AnalysisController
      · POST /analysis/conversations  可选登记会话
      · POST /analysis/chat/{conversationId} + JSON query   ← 推荐
      · GET …?query=  仍兼容，带 Deprecation 头
  → TravelAgent.chat(conversationId, userMessage)   (legacy 类名，当前金融分析实现)
  → 线性阶段 (同一 requestId)：
      PLAN → RETRIEVE → TOOL → GUARD → WRITE
      其中 RETRIEVE/TOOL/GUARD 是否物理执行，由解析后的 plan steps[*].stage 决定
      (PlanPhysicalStagePolicy；含 RETRIEVE 时隐式 GUARD)
  → SSE 输出：
      event: plan_parse (data 为 plan JSON)
      → 引用片段首包 data
      → 正文 data (流式)
      → comment 心跳
```

**超时（`app.agent`）**：`total-timeout` 包住整段 SSE 合并流；`llm-stream-timeout` 仅作用于 WRITE 的 ChatClient 流；`max-steps` 是配置下限校验（当前固定 5 步流水线，配置须 ≥5）。

## 2. 分层与 Workflow Runtime（简历核心，重点理解）

项目在 Spring AI 之上自建了一个**轻量业务 workflow runtime**。理解四层边界是讲清这个项目的关键：

| 层 | 职责 | 不负责 |
| --- | --- | --- |
| **Spring AI** | ChatClient、provider 抽象、streaming、advisor、memory、vector store、tool calling 基础设施 | 业务阶段顺序、PLAN/RETRIEVE/TOOL/GUARD 决策、合规 guard、eval 契约 |
| **Custom Workflow Runtime** (`com.travel.ai.runtime`) | 阶段编排（`LinearWorkflowRuntime`）、`WorkflowNode`/`NodeResult`、`StageTrace`/`ToolTrace`、`workflow_id`/`workflow_family` | provider SDK、底层 streaming、advisor chain、embedding runtime、Redis、路由、DAG/MQ/Temporal |
| **TravelAgent** (legacy 类名) | 当前仍承载具体 stage 业务逻辑（stagePlan/Retrieve/Tool/Guard/Write、prompt merge、empty-hit guard、finance guard、SSE 组装），约 530 行 | — |
| **Eval Runtime** (`/api/v1/eval/chat`) | 非流式评测入口、Eval Contract V1 响应、跨 target 可比性 | 主线 SSE、Redis memory、生产调度 |

### runtime 的定位（重要，别误判为过度设计）

- runtime 是**有意保留的、通往多 workflow 的种子**，不是过度设计的空壳。它已落地（`LinearWorkflowRuntime` + `PlanStageNode`/`RetrieveStageNode`/`ToolStageNode`/`GuardStageNode` + trace），用 feature flag (`app.agent.workflow-runtime.enabled`) 控制灰度。
- 当前是 **deterministic workflow orchestration**（阶段固定、顺序明确、节点结果结构化），不是 DAG/multi-agent/MQ。
- `workflow_id`/`workflow_family` 用于区分 `market_data_explain` 等业务 workflow 与普通聊天，是面向可评测性设计的。
- `WRITE` / SSE streaming **不纳入 runtime**——这是刻意决策（streaming 归 Spring AI ChatClient，runtime 只管同步阶段调度和 trace）。
- `MainChatWorkflowAdapter` 已存在，是 TravelAgent 逐步降级为 adapter/facade 路径上的一步。迁移原则：小步、测试覆盖、feature flag 灰度，**不做一次性大拆类或 package rename**。

### 为什么不直接用 LangGraph（简历问答素材）

- 生态：项目主体是 Spring Boot 3 + Spring Security + Spring AI + JDBC + Redis + pgvector，Spring 原生链路更直接。
- 已深度用 Spring AI 的 ChatClient/advisor/memory/vector store/tool，再引入另一个通用 agent framework 会增加运行时边界和调试成本。
- 业务需要 finance guard、mock 数据披露、tool governance、policy event、citation membership 等可观测语义，需贴近现有后端和 Eval Contract。
- 当前目标是「轻量业务 workflow runtime」，不是通用 agent framework / DAG runtime / 多 agent 平台。
- **若未来 Spring AI 官方 agentic/workflow API 成熟，可评估把节点执行接口适配过去**，但不在当前阶段打断主线。

### 不该自己手搓的（已遵守）

LLM SDK、底层 token stream、advisor chain、embedding runtime、provider 差异——全部交给 Spring AI。runtime 的价值只在补齐「业务阶段 + 治理事件 + 评测 trace」。

## 3. 工具治理（Tool Governance）

工具调用经一套治理协议（简历硬货：超时/限流/熔断/归因）。三层结构：

- **协议层 `ToolResult`**：统一工具返回，带 outcome（SUCCESS/TIMEOUT/ERROR/...）。
- **策略层 `ToolPolicy`**：timeout、rate limit、circuit breaker（熔断）、降级。
- **观测层 `ToolEvent` / `tool_stage` policy event**：记录每次工具调用结果，供 trace 与 eval 消费。

`outcome → error_code` 映射示例：`TOOL_TIMEOUT`、`TOOL_ERROR`、`TOOL_DISABLED_BY_CIRCUIT_BREAKER`、`RATE_LIMITED`。
工具输出有**安全预算**：注入防护、截断摘要，防止工具输出污染 prompt。

> 注：早期的 `WeatherTool` 已删除。当前真实工具是 `finance/fundamentals` 下的基本面数据源（见 §6）。

## 4. 关键可观测点

每次请求至少记录（不打印隐私全文）：检索条数 `docs.size()`、最终 prompt 长度。

**性能分段日志（`[perf]`，INFO，配合 MDC `requestId` 对齐）**：

| 字段 | 含义 |
| --- | --- |
| `rewrite_ms` | QueryRewriter.rewrite 耗时 |
| `retrieve_ms` | 多路 similaritySearch 合并/去重总耗时 |
| `doc_count` | 进入 prompt 的文档条数 |
| `llm_first_token_ms` | 首个正文 token 延迟（TTFT） |
| `llm_stream_wall_ms` | 订阅到流结束 wall 时间 |

**Trace 串联**：HTTP `X-Request-Id` → SSE `request_id`（plan_parse/stage/policy/done/error）→ `agent_task.request_id` → 异步 worker 日志 / `agent_task_event.event_json`。`RequestTraceFilter` 缺 header 时生成 UUID 并回写响应头。

**`rag_gate` policy event**：每次 GUARD 执行都发，`attrs` 含 `retrieve_hit_count`、`tool_payload_present`、`empty_hits_behavior`、`skip_llm`、`reason`；`error_code` 保留给真实门控结果如 `RETRIEVE_EMPTY`、`TOOL_NO_USABLE_PAYLOAD`。

## 5. 安全与可靠性

### 5.1 鉴权与会话隔离
- 业务接口经 `SecurityConfig` 受 Spring Security 保护。
- `POST /auth/login` 用内存用户（如 `demo/demo123`）+ `JwtService` 签发 JWT；后续 `Authorization: Bearer ...`。
- `JwtAuthFilter` 每请求解析 JWT 写入 `SecurityContext`；用户名用于：写向量 metadata 的 `user_id`、读向量时按 `user_id` 过滤，实现「谁上传谁检索」。
- **`/api/v1/eval/**`**：路径需已认证；`EvalGatewayAuthFilter` 校验 `X-Eval-Gateway-Key` 与 `app.eval.gateway-key`（`APP_EVAL_GATEWAY_KEY`）通过后注入 `eval-gateway` 主体。未配置网关密钥时评测接口 401。

### 5.2 限流
- `RateLimitingFilter`（全局 OncePerRequestFilter，在 JwtAuthFilter 之后）：对 `/analysis/chat/**`、`/finance/chat/**`、legacy `/travel/chat/**` 用 Bucket4j + Caffeine 为每用户/IP 独立 token bucket。
- 默认每用户/IP 每分钟 5 次，超额 HTTP 429，Body 同形 `{"error":"RATE_LIMITED",...}`。登录用户 key 为 `user:{username}`，匿名退化为 IP。

### 5.3 超时与降级
- LLM 调用：内容流 `Flux<String>` 上 `.timeout(...)`，超时经 `.onErrorResume(...)` 返回系统提示并与心跳流合并，避免 SSE 永久挂起。`doFinally` 清理 MDC `requestId`。
- 外部数据源（基本面）：超时/异常时降级（见 §6 `MockFundamentalsDataSource`），不让整轮请求崩。

### 5.4 日志噪音治理
- SSE 异步 dispatch 会让 Tomcat 收尾再次触发 Security filter chain。`SecurityConfig` 用 `securityMatcher(req -> req.getDispatcherType() == REQUEST)` 仅对真实 HTTP 请求应用，避免 SSE 收尾的多余 `AccessDeniedException` 日志。

### 5.5 Actuator 探活
- 仅暴露 `health`、`info`；`show-details`/`show-components` 为 `when_authorized`（匿名只得聚合状态，带 JWT 见组件详情）。
- 开启 liveness/readiness probes，`/actuator/health/liveness`、`/readiness` 匿名 200。`SecurityConfig` 对 `/actuator/health/**`、`/actuator/info` 用 `permitAll()`，便于 LB / K8s 健康检查。

### 5.6 SSE 工程化
- 返回 `Flux<ServerSentEvent<String>>`，区分业务 `data` 与保活 `comment`。
- 心跳：`Flux.interval` 按 `app.sse.heartbeat-seconds`（默认 15s）发 comment；正文流经 `.share()` 与心跳 `Flux.merge`，避免重复订阅导致**重复调用 LLM**；正文结束用 `takeUntilOther(...)` 停心跳。
- 断线：下游取消触发 `doOnCancel` 日志；Tomcat 收尾偶发 IOException 属正常，与业务失败区分。
- 响应头：`Cache-Control: no-cache, no-store`、`X-Accel-Buffering: no`。

## 6. 真实基本面数据（第一块砖，已落地）

包 `com.travel.ai.finance.fundamentals`，把 mock 行情换成真实美股基本面：

- `FundamentalsSnapshot` — 结构化领域对象（BigDecimal、全可空、持久化无知，不带 JPA 注解）。
- `FundamentalsDataSource` 接口 + `FmpFundamentalsDataSource`（FMP **stable** 版 `/profile` + `/ratios-ttm`，RestClient）+ `MockFundamentalsDataSource`（降级）+ `CachingFundamentalsDataSource`（Caffeine 防烧额度）。
- `FundamentalsTextRenderer`（结构 → LLM 文本）、`FmpProperties` / `FundamentalsConfig`。
- key 走 `FMP_API_KEY` 环境变量，无 key 自动降级 mock。FMP 免费档 250 次/天，已用真实 key 验证 AAPL 取数与字段映射正确。

## 7. LLM 供应商

- **chat 主模型 = Anthropic Claude**（经公司中转 `ANTHROPIC_BASE_URL`，token `ANTHROPIC_AUTH_TOKEN`，模型 claude-sonnet-4-5，Anthropic 原生协议，`spring-ai-starter-model-anthropic`）。
- **embedding = DashScope**（RAG 用；当前无 key 未真跑）。
- 业务代码供应商中立（Spring AI ChatClient/ChatModel 抽象）。坑：① DashScope + Anthropic 两个 ChatModel bean 冲突 → `application.yml` 用 `spring.autoconfigure.exclude` 排除 DashScope 的 ChatAutoConfiguration（保留其 embedding 自动配置）；② AnthropicApi 构造校验 base-url 非空，test profile 须给占位非空 base-url。**生产必须配 `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`，否则启动崩。**

## 8. 向量存储 / RAG

- 用 **Spring AI 官方 `PgVectorStore`**（已删自研实现）。`vector_store` 表，`embedding vector(1024)`，cosine，HNSW；按 `user_id` 隔离（`metadata::jsonb @@ jsonpath` 过滤）。
- RAG 编排（QueryRewriter 改写、多路检索合并去重）当前仍自研——这是后续可换 Spring AI 模块化 RAG 的候选（见 ROADMAP）。

## 9. 部署（Docker Compose）

- 根目录 `docker-compose.yml`：`app` + `postgres`（`pgvector/pgvector:pg16`）+ `redis`，同网络服务名互访。
- 库表由 **Flyway** 启动时执行 `classpath:db/migration`。
- `.env`（由 `.env.example` 复制）注入 `SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`、`APP_JWT_SECRET`、LLM key 等。
- 宿主机映射：`8081`（应用）、`5433→5432`（PG）、`16379→6379`（Redis，避开 Windows 排除端口段）；compose 内仍用 `redis:6379`。

## 关键源码入口

- SSE 入口：`controller/TravelController.java`（legacy 名）、`AnalysisController`
- 主线编排 + RAG：`agent/TravelAgent.java`
- workflow runtime：`runtime/LinearWorkflowRuntime.java` + `runtime/node/*`
- 查询改写：`agent/QueryRewriter.java`
- 基本面数据：`finance/fundamentals/*`
- 评测：`eval/EvalChatController.java`、`EvalChatService.java`
