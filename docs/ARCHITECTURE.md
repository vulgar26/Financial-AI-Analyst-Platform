# ARCHITECTURE — 最简链路说明（与代码同步）

本项目的核心是 **固定线性编排下的可解释 RAG + SSE**：在 `TravelAgent` 内按阶段推进（见 §1.1），并补齐鉴权、会话隔离、限流、超时、Actuator、SSE 心跳。本文以当前源码和 `application.yml` 为准，历史计划与阶段记录已归档到 `docs/archive/`。

## 1. 请求链路（文本流程图）

客户端请求（SSE，经鉴权与限流）  
→ `TravelController`：可选 `POST /travel/conversations` 登记会话；**推荐** `POST /travel/chat/{conversationId}` + JSON `query`（路径校验、`max-query-chars`；`app.conversation.require-registration` 时校验 Redis 归属）；`GET …?query=` 仍兼容并带 `Deprecation`  
→ `TravelAgent.chat(conversationId, userMessage)`  
→ **线性阶段**（同一 `requestId` 日志）：`PLAN`（结构化计划 JSON，可配置 LLM）→ `RETRIEVE` → `TOOL` → `GUARD` → `WRITE`；其中 `RETRIEVE`/`TOOL`/`GUARD` 是否**物理执行**由解析后的 plan `steps[*].stage` 决定（`PlanPhysicalStagePolicy`，含 `RETRIEVE` 时隐式 `GUARD`）  
→ SSE：首段 **`event: plan_parse`**（`data` 为 JSON，字段与评测 `meta.plan_parse_*` / 日志 `[plan]` 对齐）→ `引用片段` 首包 `data` → 正文 `data` → `comment` 心跳

**超时（`application.yml` → `app.agent`）**：`total-timeout` 包住整段 SSE 合并流；`llm-stream-timeout` 仅作用于 WRITE 的 `ChatClient` 流；`tool-timeout` 作用于天气 OkHttp；`max-steps` 为配置下限校验（当前固定流水线为 5 步，配置须 ≥5）。

### 1.1 评测路径（对齐主线）

- **评测** `POST /api/v1/eval/chat` 的线性占位管线与 **主线 SSE** 同为 **`PLAN→RETRIEVE→TOOL→GUARD→WRITE`**（`EvalLinearAgentPipeline`；短路路径仍可能只返回子序列，如 `PLAN→GUARD`）。  
- 响应 **`meta`** 可含 **`recovery_action`** / **`self_check`**（`EvalReflectionSupport` 占位；`app.eval.reflection-meta-enabled` 控制；与 `replan_count=0` 正交）。  

## 2. 单链路约束（为什么要这样做）

- 每次请求仅保留 **一套** 检索与上下文注入逻辑，避免重复检索导致的成本与延迟不可控。
- 检索到的文本进入 `promptBase`；计划 JSON 与工具观察块在 `TOOL` 末（或 TOOL 被跳过时等价路径）合并为 `finalPromptForLlm`；若 `app.memory.long-term.enabled` 与 `inject-into-prompt` 为真，则在合并结果前可选附加 **用户画像** 前缀（`UserProfileService`，不打印画像全文）。随后进入 `WRITE`（门控命中时可能跳过 LLM，仅下发澄清）。
- 若 `app.memory.auto-extract.enabled` 与 `after-chat` 为真，则在整段 SSE `onComplete` 后于 boundedElastic 线程异步调用 `ProfileExtractionCoordinator`（用户名在订阅线程预捕获，异步路径不读 `SecurityContext`）。
- `DELETE /travel/profile?clearChatMemory=true` 可在删 PG 画像后按前缀清理 `RedisChatMemory`（可选限定 `conversationId`），见 `RedisChatMemory#deleteAllConversationsForUser` / `clearForUser`。

## 3. 关键可观测点（最小版）

一次请求至少记录以下信息（不打印完整隐私内容）：

- 检索条数：`docs.size()`
- 最终 prompt 长度：`TravelAgent` 内在进入 LLM 前记录 `finalPromptForLlm.length()`（门控路径另见日志）

**性能分段（`TravelAgent`，INFO 级别，前缀 `[perf]`）**：与 MDC 中的 `requestId` 一起便于按请求对齐日志。

| 字段 | 含义 |
|------|------|
| `rewrite_ms` | `QueryRewriter.rewrite` 耗时（毫秒） |
| `retrieve_ms` | 多路 `similaritySearch` 合并/去重总耗时 |
| `doc_count` | 进入本轮 prompt 的文档条数 |
| `llm_first_token_ms` | 流式订阅后首个正文 token 的延迟（TTFT） |
| `llm_stream_wall_ms` | 从订阅到流结束的 wall 时间（`doFinally` 的 signal） |

未接入 Micrometer/Prometheus 时，用上述日志即可做本机对比与慢请求粗定位。

### 3.1 Request trace and guard policy fields

- `RequestTraceFilter` reads `X-Request-Id`; if it is missing, the server generates a UUID. The value is written to MDC as `requestId` and echoed in the response header `X-Request-Id`.
- `TravelAgent` reuses the current MDC `requestId` when present, and still writes the same id explicitly into SSE `request_id` fields. This is important for reactive/SSE paths where thread-local MDC alone is not enough.
- Trace join path: HTTP `X-Request-Id` -> SSE `request_id` (`plan_parse`, `stage`, `policy`, `done/error`) -> `agent_task.request_id` -> async worker logs and `agent_task_event.event_json`.
- `rag_gate` policy events are emitted on every GUARD execution. `attrs` include `retrieve_hit_count`, `tool_payload_present`, `empty_hits_behavior`, `skip_llm`, and `reason`. `error_code` remains reserved for actual gate/skip outcomes such as `RETRIEVE_EMPTY` or `TOOL_NO_USABLE_PAYLOAD`.

## 4. 相关源码入口

- 对话 SSE 入口：`src/main/java/com/travel/ai/controller/TravelController.java`
- 主线编排与 RAG：`src/main/java/com/travel/ai/agent/TravelAgent.java`
- 查询改写：`src/main/java/com/travel/ai/agent/QueryRewriter.java`
- 向量存储：`src/main/java/com/travel/ai/config/PgVectorStore.java`（`metadata` JSON；`similaritySearch` 按 `user_id` 过滤）
- 评测 HTTP：`src/main/java/com/travel/ai/eval/EvalChatController.java`、`EvalChatService.java`
- 评测网关 Filter：`src/main/java/com/travel/ai/security/EvalGatewayAuthFilter.java`
- 手工 RAG 回归表：`docs/eval.md`

## 5. 安全与可靠性（Week 2 进展）

### 5.1 鉴权与会话隔离

- 业务接口（`/travel/**`、`/knowledge/**` 等）通过 `SecurityConfig` 受 Spring Security 保护。
- **`/api/v1/eval/**`**：路径需 **已认证**；由 `EvalGatewayAuthFilter` 在校验 **`X-Eval-Gateway-Key`** 与 `app.eval.gateway-key`（环境变量 `APP_EVAL_GATEWAY_KEY`）通过后注入 `eval-gateway` 主体（与 JWT 可并存于不同请求）。未配置网关密钥时评测接口 **401**。
- `POST /auth/login` 使用内存用户（如 `demo/demo123`）与 `JwtService` 签发 JWT，客户端在后续请求中通过 `Authorization: Bearer ...` 访问。
- `JwtAuthFilter` 在每次请求前解析 JWT，将当前用户写入 `SecurityContext`，`TravelAgent` 与 `KnowledgeServiceImpl` 从中读取用户名，用于：
  - 写入向量 metadata（`user_id` 字段）；
  - 读取向量时按 `user_id` 过滤，实现“谁上传谁检索”。

### 5.2 限流

- `RateLimitingFilter` 是一个全局 `OncePerRequestFilter`，在 `JwtAuthFilter` 之后执行：
  - 针对 `/travel/chat/**` 使用 Bucket4j + Caffeine 为每个用户/IP 创建独立 token bucket。
  - 默认策略：每用户/IP 每分钟 5 次请求（可配置），超额时立即返回 HTTP 429，Body 与鉴权/REST 同形：`{"error":"RATE_LIMITED","message":"请求过于频繁，请稍后再试"}`（`JsonApiErrorSupport`）
  - 登录用户用 `user:{username}` 作为限流 key，匿名用户退化为按 IP 限流。

### 5.3 超时与降级

- **LLM 调用**（`TravelAgent.chat`）：
  - 在 **内容流** `Flux<String>` 上增加 `.timeout(Duration.ofSeconds(20))`，整体超时后通过 `.onErrorResume(...)` 记录错误并返回一条系统提示，再封装为 `ServerSentEvent` 与心跳流合并输出；避免 SSE 永久挂起。
  - `doFinally` 清理 MDC 中的 `requestId`，保证日志上下文不串号。

- **天气工具**（`WeatherTool`）：
  - 通过 `application-local.yml` 配置 `weather.timeout-ms` 与 `weather.api-url`，初始化 OkHttpClient 的 connect / read / write / call timeout。
  - 若未配置 `api-url`，走本地模拟天气文案；若配置了但调用超时或异常，则记录 warn/error 日志并返回“查询超时/暂时不可用”的降级文本。

### 5.4 日志噪音治理

- 由于 SSE 使用异步 dispatch，Tomcat 在 async 收尾阶段会再次触发 Spring Security filter chain。
- 通过在 `SecurityConfig` 中增加 `securityMatcher(request -> request.getDispatcherType() == REQUEST)`，仅对真正的 HTTP 请求（`DispatcherType.REQUEST`）应用 SecurityFilterChain，避免在 SSE 收尾阶段出现多余的 `AccessDeniedException` 与 “response already committed” 日志。

### 5.5 运行探活（Actuator）

- 依赖：`spring-boot-starter-actuator`。
- `application.yml` 中 `management.endpoints.web.exposure.include` 仅暴露 `health`、`info`；`management.endpoint.health.show-details` / `show-components` 为 `when_authorized`，匿名调用只得到聚合状态（如 `{"status":"UP"}`），带 JWT 时可看 DB、Redis 等组件详情。
- 配置了 `livenessstate` / `readinessstate` 用于 `health` 语义判断，并开启了 `management.endpoint.health.probes.enabled`；因此子路径 `/actuator/health/liveness` 与 `/actuator/health/readiness` 返回 `200`（匿名可访问）。
- `SecurityConfig` 对 `/actuator/health/**` 与 `/actuator/info` 使用 `permitAll()`，负载均衡与 Docker/K8s 健康检查无需携带 Token。

更细的 Actuator 概念说明见：`ACTUATOR_HEALTH_BASICS.md`。

### 5.6 SSE 工程化（心跳与断线）

- **返回模型**：`TravelController` / `TravelAgent.chat(...)` 使用 **`Flux<ServerSentEvent<String>>`**，而不是裸 `Flux<String>`，以便区分 **业务 `data`** 与 **保活 `comment`**（符合 SSE 文本格式：`data:` 与以 `:` 开头的注释行）。
- **心跳**：`Flux.interval` 按 `app.sse.heartbeat-seconds`（默认 15s）发送 `ServerSentEvent.comment("keepalive")`；正文流 `contentFlux` 经 **`.share()`** 与心跳合并（`Flux.merge`），避免重复订阅导致 **重复调用 LLM**。正文结束后通过 **`takeUntilOther(contentFlux.then())`** 停止心跳。
- **断线**：客户端关闭连接时，下游取消订阅会触发 **`doOnCancel`** 日志（`SSE 订阅已取消…`）。Tomcat 在收尾写 Socket 时偶发 **`IOException`（连接被对端中止）** 属于常见现象，与业务失败不同，可在运维上降级或忽略。
- **响应头**：`TravelController` 设置 `Cache-Control: no-cache, no-store` 与 `X-Accel-Buffering: no`，减轻缓存与反向代理缓冲对流式响应的影响。

## 6. Docker Compose（Week 3）

- 仓库根目录 `docker-compose.yml` 定义 **`app` + `postgres`（`pgvector/pgvector:pg16`）+ `redis`**，同一默认网络内通过服务名互访。
- **库表结构**：由 **Flyway** 在应用启动时执行 `classpath:db/migration`（当前 **`V1__init_pgvector.sql`**：创建 `vector` 扩展与 **`vector_store`**，列与 `PgVectorStore` 一致）。
- 环境变量通过 **`.env`**（由 `.env.example` 复制）注入 **`SPRING_DATASOURCE_*`、`SPRING_DATA_REDIS_*`、`APP_JWT_SECRET`、`SPRING_AI_DASHSCOPE_API_KEY`** 等，避免把密钥写进镜像。
- 宿主机映射 **`8081`**（应用）、**`5433→5432`**（Postgres）、**`16379→6379`**（Redis）。Redis 不使用宿主机 `6379`，避免 Windows Hyper-V 排除端口段导致 Docker bind 失败；compose 内服务仍通过 **`redis:6379`** 访问。
