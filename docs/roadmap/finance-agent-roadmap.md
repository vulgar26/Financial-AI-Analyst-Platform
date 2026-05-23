# Finance AI Agent Roadmap

## 项目定位

当前项目虽然名为 Travel AI Planner，但核心已经是一个可观测的 Agentic RAG 后端：包含线性 Agent workflow、SSE 流式输出、工具治理、评测接口、guard/gate、Redis 会话记忆、PostgreSQL/pgvector、query rewrite 和用户隔离。

长期方向可以演进为 Finance AI Analyst / Financial Agent，服务于金融知识学习、新闻分析、财报/研报问答、市场数据分析、图表解读、风险提示和研究报告生成。系统定位是金融研究辅助平台，不是自动交易或荐股系统。

## 当前状态

强 travel 绑定主要集中在命名、路由、prompt 和少量工具：

- `TravelAgent`、`TravelController`、`TravelChatRequest`、`TravelKnowledgeController`。
- `/travel/chat/**`、`/travel/knowledge/**`、`/travel/profile/**`。
- `SYSTEM_PROMPT`、`QueryRewriter` prompt、Plan prompt 中的金融研究、公司分析、风险因素、数据核验语义。
- `WeatherTool` 作为旅行场景工具。
- 前端文案、测试类名、README 项目叙事。

通用基础设施可以继续复用：

- `PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE` 的线性 Agent workflow。
- RAG 上传、切分、向量化、pgvector 检索和 metadata 用户过滤。
- SSE 阶段事件、policy 事件、引用片段、心跳、done/error 事件。
- `ToolExecutor`、`ToolRateLimiter`、`ToolCircuitBreaker`、`ToolObservability`。
- `EvalChatController`、`EvalChatService`、`EvalLinearAgentPipeline` 和相关 eval DTO。
- `RetrieveEmptyHitGate`、安全门控、JWT、限流、Redis ChatMemory、Docker Compose、Testcontainers。

## 长期目标

目标架构应围绕金融研究任务组织：

```text
User / Frontend
  -> FinancialAgentController
  -> Agent Orchestrator
     -> PLAN
     -> QUERY_REWRITE
     -> RETRIEVE
     -> TOOL_CALL
     -> COMPLIANCE_GUARD
     -> ANALYZE
     -> REPORT_WRITE
  -> SSE: stage / policy / sources / tool / answer / done
```

核心模块：

- Market data ingestion：接入行情、指数、财务指标、宏观数据，写入结构化表和缓存。
- News ingestion：新闻抓取、去重、标签、摘要、embedding。
- Filings/research ingestion：财报、公告、研报解析、chunk、metadata 标准化。
- RAG：面向财报、研报、公告、新闻、制度文档的证据检索。
- Tool calling：统一调用新闻、财报、行情、搜索、图表分析等工具。
- Report generation：生成公司分析、财报摘要、事件分析、行业周报和风险清单。
- Eval/harness：覆盖事实一致性、引用覆盖、合规拒答、工具失败、行情时效。
- SSE streaming：实时展示分析阶段、引用来源、工具状态和最终报告。
- Cache layer：用 Redis 缓存行情、新闻检索、工具响应、长任务状态。

适合接入的金融 tool 类型：

- 新闻：`NewsSearchTool`、`NewsSentimentTool`、`EventTimelineTool`。
- 财报：`FilingSearchTool`、`FinancialStatementTool`、`EarningsCallTool`、`ReportParserTool`。
- 市场行情：`QuoteTool`、`HistoricalPriceTool`、`IndexMarketTool`、`MacroDataTool`。
- 图表分析：`ChartVisionTool`、`TableExtractionTool`、`ChartRenderTool`。
- 搜索与实体归一：`WebSearchTool`、`CompanyProfileTool`、`EntityResolverTool`。

## V1

目标：完成领域迁移，但不改变当前主干架构。

- 将系统 prompt、query rewrite prompt、plan prompt 从旅行改为金融分析语义。
- 将 `TravelAgent`、`TravelController`、`TravelChatRequest` 等命名迁移为 `FinancialAnalystAgent`、`AnalysisController`、`AnalysisChatRequest`。
- 将 `/travel/chat/**` 长期迁移到 `/analysis/chat/**` 或 `/finance/chat/**`，保留兼容期策略。
- 将 `WeatherTool` 替换或并行为 `MarketDataTool` 的 mock/真实实现。
- 为知识库 metadata 增加长期设计字段：`source_type`、`ticker`、`market`、`period`、`publish_date`、`provider`。
- 增加金融合规 guard：拒绝自动交易、收益承诺、内幕信息、市场操纵请求。
- 扩展 eval 数据集：财报问答、新闻事件、RAG 空命中、工具失败、合规拒答。

## V2

目标：从聊天问答升级为金融研究工作台。

- 增加 market data、news、filings 的 ingestion pipeline。
- 引入异步 analysis job，用于长报告、批量分析、行业周报。
- 建立 `DocumentSource` / `ResearchDocument` 抽象，支持财报、研报、公告、新闻、用户笔记。
- 建立 tool registry，统一管理 provider、timeout、quota、cache、policy。
- 前端展示 sources、tool trace、job status、报告历史和风险提示。
- 支持报告产物落库，包含引用、生成参数、数据时间和 eval 标记。

## V3

目标：形成可运营、可审计、可评测的金融 AI Agent 平台。

- 建立金融 eval dataset 和定期回归任务。
- 产出质量指标：faithfulness、source coverage、tool success rate、latency、compliance block rate。
- 引入多租户知识库、团队权限、审计日志和数据源权限控制。
- 将 compliance policy 独立配置化，支持规则版本、命中原因和可追溯 policy event。
- 按任务拆分模型策略：rewrite、planner、vision、writer、evaluator 使用不同模型或参数。
- 支持报告草稿、引用审计、导出、反馈闭环和人工复核流程。

## 风险与边界

- 不做自动交易，不接下单 API，不生成可直接执行的交易指令。
- 不承诺收益，不输出“稳赚”“保证收益”“确定目标价”等确定性结论。
- 不提供个性化投资建议，避免基于个人资产情况给出买入/卖出/持有建议。
- 对实时或准实时市场数据必须标注 `as_of_time`、来源和延迟。
- 对证据不足、RAG 空命中、工具失败、数据过期的场景必须澄清或降级。
- 金融分析输出应默认包含风险提示和“非投资建议”声明。
