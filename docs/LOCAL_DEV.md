# LOCAL_DEV — 本地运行、演示与排错

本文覆盖：本地启动（IDEA + Docker 依赖）、Docker Compose 全套演示、前端 demo、Actuator 健康检查、常见错误。
环境：Windows + Docker Desktop + IDEA（命令以 PowerShell 为主）。

---

## A. 本地开发（推荐：Compose 起依赖 + IDEA 跑后端 + Vite 跑前端）

### 1. 起依赖

```powershell
docker compose up -d postgres redis
```

端口映射：
- Postgres 容器 `5432` → 宿主机 `localhost:5433`
- Redis 容器 `6379` → 宿主机 `localhost:16379`

### 2. IDEA 运行后端主类

主类：`com.travel.ai.TravelAiApplication`（legacy 名）。建议环境变量：

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ragent
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=16379
APP_JWT_SECRET=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
SPRING_AI_DASHSCOPE_API_KEY=<embedding-key>
ANTHROPIC_BASE_URL=<chat 中转地址>
ANTHROPIC_AUTH_TOKEN=<chat token>
FMP_API_KEY=<可选，无则基本面降级 mock>
```

> 生产/真跑 chat 必须配 `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`，否则启动崩（AnthropicApi 校验 base-url 非空）。

Windows / IDEA 环境变量用分号 `;` 分隔，不要用 Linux 的空格写法。变量多时在 Run Configuration 的 Environment variables 弹窗里逐项填，减少分隔符错误。

### 3. 验后端

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health
```

### 4. 起前端

```powershell
cd frontend
npm install
npm run dev
```

访问 `http://localhost:5173`，前端 `/api` 代理到 `http://127.0.0.1:8081`。

---

## B. Docker Compose 全套演示（60 秒）

### 0. 环境变量

```powershell
copy .env.example .env
# 编辑 .env：至少填 embedding key + chat 的 ANTHROPIC_*，APP_JWT_SECRET 与示例一致
```

### 1. 起全套

```powershell
docker compose up -d --build
```

根 `docker-compose.yml` 定义 `app` + `postgres`（pgvector/pgvector:pg16）+ `redis`。库表由 Flyway 启动时执行 `classpath:db/migration`。宿主机映射 `8081`、`5433→5432`、`16379→6379`。

### 2. 健康检查（匿名可访问）

```powershell
curl.exe http://localhost:8081/actuator/health   # 应含 {"status":"UP"}
```

### 3. 登录拿 token

```powershell
$resp = Invoke-RestMethod -Method Post -Uri "http://localhost:8081/auth/login" `
  -ContentType "application/json" -Body '{"username":"demo","password":"demo123"}'
$token = $resp.token
```

### 4. 上传知识（向量检索用）

```powershell
curl.exe -X POST "http://localhost:8081/knowledge/upload" `
  -H "Authorization: Bearer $token" -F "file=@test.txt"
```

成功返回 JSON（`ok`、`fileName`、`chunkCount`、`message`）。当前仅支持 `.txt`。

### 5. SSE 对话（推荐 POST + JSON）

```powershell
curl.exe -N -X POST "http://localhost:8081/analysis/chat/demo-conv" `
  -H "Authorization: Bearer $token" `
  -H "Accept: text/event-stream" `
  -H "Content-Type: application/json" `
  --data-raw "{\"query\":\"请基于我上传的资料，总结这家公司的近期财报亮点、风险因素和需要继续核验的数据。\"}"
```

终端持续输出 SSE：流首可能含 `event: plan_parse` + 一行 `data:`（JSON 元数据），随后为引用与正文 `data:` 行。`query` 长度受 `app.conversation.max-query-chars`（默认 8192）限制，超出返回 400。
GET `…?query=` 仍兼容但已弃用（返回 `Deprecation: true`），新脚本用 POST。

---

## C. 前端 Demo 说明

`frontend`（Vite + React + Fetch），开发时 Vite 代理 `/api` → `http://127.0.0.1:8081`。API 集中在 `frontend/src/api.js`。

UI 区域：Header（产品名/状态/错误）、登录（`demo/demo123`）、知识上传（`.txt`）、知识列表、Chat（流式 SSE）、Agent trace（`plan_parse` + `PLAN→RETRIEVE→TOOL→GUARD→WRITE` 阶段事件）、Sources、用户画像、Feedback。

主要接口（首选 `/analysis/**`）：

| 能力 | 接口 |
| --- | --- |
| 登录 | `POST /auth/login` |
| 建会话 | `POST /analysis/conversations` |
| 上传知识 | `POST /knowledge/upload` |
| 知识列表 / 删除 | `GET /analysis/knowledge` / `DELETE /analysis/knowledge/{fileId}` |
| SSE 对话 | `POST /analysis/chat/{conversationId}` |
| 画像（查/提取/确认/丢弃/重置） | `GET /analysis/profile`、`POST /analysis/profile/extract-suggestion`、`GET/POST/DELETE …/pending-extraction`、`DELETE /analysis/profile` |
| 反馈 | `POST /analysis/feedback`、`GET /analysis/feedback?limit=&offset=` |

手动验收：起依赖 → 跑后端 → 健康检查 → 起前端 → `demo/demo123` 登录 → 上传 .txt → 发金融问题确认流式 → 确认 UI 标注「研究/教育用途、非投资建议、不执行交易、需人工复核」→ 确认网络请求走 `/analysis/**`。

---

## D. Actuator 健康检查要点

- 仅暴露 `health`、`info`；`show-details`/`show-components` = `when_authorized`：匿名只得 `{"status":"UP"}`，带 JWT 见 DB/Redis 等组件详情。
- 开了 liveness/readiness probes：`/actuator/health/liveness`、`/readiness` 匿名返回 200。
- `SecurityConfig` 对 `/actuator/health/**`、`/actuator/info` 用 `permitAll()`，LB / Docker / K8s 健康检查无需 token。
- 健康状态是聚合的：任一组件 DOWN 则整体 DOWN。自定义检查实现 `HealthIndicator`，注意加超时避免拖垮探活。

---

## E. 常见错误

**连 `127.0.0.1:5432` refused** — 后端连了默认 5432，但 compose 暴露的是 5433。改 `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ragent`，并 `docker compose ps postgres` 确认容器起了。

**`RedisConnectionFailureException`** — IDEA 仍连 6379 / 容器没起 / Windows 排除端口致 `6379:6379` 失败。改 `SPRING_DATA_REDIS_PORT=16379`，`docker compose ps redis` 确认。Docker 内部服务仍用 `redis:6379`。

**环境变量分隔符错误** — Windows/IDEA 多变量用分号 `;`，别用 Linux 的空格形式。变量多就用 IDEA Run Configuration 弹窗逐项填。
