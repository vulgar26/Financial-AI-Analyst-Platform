# Financial AI Analyst Platform

> API naming note: `/analysis/**` is the preferred API for new finance analyst clients. `/finance/**` is the finance-semantic alias. `/travel/**` remains a legacy-compatible endpoint for existing callers and should not be used by new code.

Financial AI Analyst Platform 是一个面向金融研究分析场景的 Agentic RAG + SSE 后端项目。它不是自动交易系统，也不是简单包装大模型接口，而是围绕 Spring Boot 3、Spring AI Alibaba、PostgreSQL/pgvector、Redis 和 JWT，把知识上传、检索增强、线性 Agent 编排、流式输出、用户隔离、工具治理、合规边界和评测接口串成一条可演示、可测试的后端链路。

> Legacy note: 项目目录和部分兼容类仍保留 `travel-ai-planner` / `/travel/**` 命名，以保证现有前端、测试和 API 不被破坏。新的金融分析别名已通过 `/analysis/**` 与 `/finance/**` 暴露。

[![CI](https://github.com/vulgar26/travel-ai/actions/workflows/ci.yml/badge.svg)](https://github.com/vulgar26/travel-ai/actions/workflows/ci.yml)

## 核心链路

```text
PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE
```

- `PLAN`：生成或兜底解析结构化计划，约束后续阶段顺序。
- `RETRIEVE`：对用户问题做 query rewrite，执行 pgvector 检索并按当前用户过滤知识片段。
- `TOOL`：按计划调用受控工具，包含超时、限流、熔断和降级结果；当前包含 legacy 天气工具和 mock 市场数据工具。
- `GUARD`：对检索零命中等低依据场景做门控，避免直接编造答案。
- `WRITE`：通过 SSE 返回阶段事件、引用片段、正文 token、心跳、完成或错误事件。

核心实现入口：

- `src/main/java/com/travel/ai/agent/FinancialAnalystAgent.java`
- `src/main/java/com/travel/ai/agent/TravelAgent.java`（legacy-compatible implementation）
- `src/main/java/com/travel/ai/controller/AnalysisController.java`
- `src/main/java/com/travel/ai/controller/TravelController.java`（legacy route）
- `src/main/java/com/travel/ai/config/PgVectorStore.java`
- `src/main/java/com/travel/ai/eval/EvalChatController.java`
- `src/main/java/com/travel/ai/security/SecurityConfig.java`

## 技术栈

- 后端：Java 21、Spring Boot 3、Spring Security、Spring AI Alibaba、Spring JDBC、Flyway、Actuator
- 数据：PostgreSQL + pgvector、Redis
- 前端：Vite、React 18、Fetch API、手写 text/event-stream 解析
- 测试与部署：JUnit 5、Spring Boot Test、Testcontainers、Docker Compose、GitHub Actions

## 已实现能力

- JWT 登录与 Spring Security 保护业务接口。
- 服务端签发并登记 `conversationId`，支持按用户隔离会话。
- `.txt` 知识上传、分块、向量化，并写入 PostgreSQL + pgvector。
- 检索 metadata 写入 `user_id`，查询时按当前登录用户过滤。
- Redis ChatMemory 保存短期对话上下文。
- SSE 流式聊天，包含阶段事件、心跳、引用片段、完成和错误事件。
- query rewrite、多路向量检索、合并去重和引用返回。
- 检索零命中门控，可返回澄清而不是无依据生成。
- Tool governance：工具超时、限流、熔断、观测日志、统一 outcome/error_code。
- Mock `MarketDataTool` 占位，用于金融分析工具链路验证，不接真实金融 API。
- 用户研究偏好画像读取、抽取建议、确认写入、忽略和重置。
- 用户反馈提交与分页查询，支持关联 `request_id` / eval 字段。
- 评测接口 `POST /api/v1/eval/chat`，用于非流式 JSON 回归验证。
- Docker Compose 启动 Postgres/Redis/App，Flyway 管理数据库迁移。
- JUnit、Spring Boot Test、Testcontainers 和 GitHub Actions CI。
- Vite + React 最小演示前端，默认使用主推 `/analysis/**` API；`/travel/**` 仅作为 legacy-compatible endpoint 保留。

## API 入口

新的金融分析别名：

- `POST /analysis/conversations`
- `POST /analysis/chat/{conversationId}`
- `GET /analysis/chat/{conversationId}?query=...`
- `GET /analysis/knowledge`
- `DELETE /analysis/knowledge/{fileId}`
- `GET /analysis/profile`
- `POST /analysis/profile/extract-suggestion`
- `POST /analysis/feedback`
- `GET /analysis/feedback?limit=5&offset=0`
- 同等 `/finance/**` alias

兼容保留（legacy-compatible，不建议新代码继续使用）：

- `POST /travel/conversations`
- `POST /travel/chat/{conversationId}`
- `GET /travel/chat/{conversationId}?query=...`
- `GET /travel/knowledge`
- `DELETE /travel/knowledge/{fileId}`
- `GET /travel/profile`
- `POST /travel/feedback`

## 快速启动

推荐本地开发方式：Docker Compose 启动依赖，IDEA 启动后端主类，Vite 启动前端。

1. 复制环境变量模板并填写本地密钥：

```powershell
Copy-Item .env.example .env
```

2. 启动依赖服务：

```powershell
docker compose up -d postgres redis
```

3. 在 IDEA 中运行后端主类：

```text
com.travel.ai.TravelAiApplication
```

本地 IDEA 连接宿主机端口：

- Postgres: `localhost:5433`
- Redis: `localhost:16379`
- Backend: `http://localhost:8081`

4. 启动前端：

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址为 `http://localhost:5173`，Vite 将 `/api` 代理到 `http://127.0.0.1:8081`。

更完整的 Windows / Docker Desktop / IDEA 启动说明见 [docs/LOCAL_DEV.md](docs/LOCAL_DEV.md)。

## 金融合规边界

系统定位为金融研究和教育辅助，不构成投资建议：

- 不做自动交易，不接下单 API。
- 不承诺收益，不输出“稳赚”“保证收益”等确定性表述。
- 不提供个性化买入/卖出/持有建议。
- 对行情、新闻、财务数据必须尽量标注来源和数据时点。
- 证据不足、检索空命中、工具失败或数据过期时应澄清或降级。

合规边界在自用前提下的角色说明，以及项目定位，见 [docs/VISION.md](docs/VISION.md)。

## Roadmap

近期砖块与远期愿景见 [docs/ROADMAP.md](docs/ROADMAP.md)。完整文档导航见 [docs/README.md](docs/README.md)。

## 已知限制

- 当前前端是最小演示界面，不是完整产品级金融研究工作台。
- 账号体系使用演示账号，未实现完整注册、密码找回、角色权限管理。
- 知识上传主要支持 `.txt`，PDF、网页、Markdown、财报表格解析仍未完成。
- Mock `MarketDataTool` 不提供真实行情，不得用于交易或投资决策。
- RAG 质量增强仍以向量检索为主，BM25/RRF、rerank、严格引用覆盖率校验属于后续方向。
- 评测接口已具备结构化输出，但没有内置可视化 dashboard。
- 生产级密钥托管、反向代理、监控告警和部署治理仍需按真实环境补齐。

## 后续计划

- 完善知识库管理闭环：文件列表、删除、重复上传处理、重建索引和来源筛选。
- 引入混合检索与质量提升：关键词检索、RRF、rerank、score threshold 和引用一致性检查。
- 增强金融文档解析：Markdown、PDF、网页抓取、财报表格和结构化 metadata。
- 扩展金融工具占位：新闻、财报、行情、图表分析和实体归一。
- 补齐 eval 报告化：固定题集导入、run/result 落库、report/compare、失败归因统计。
- 升级可观测能力：将当前分段日志沉淀为 Micrometer 指标和更稳定的 trace 字段。
- 完善生产化说明：密钥管理、反向代理、健康探针、日志脱敏和部署 runbook。
