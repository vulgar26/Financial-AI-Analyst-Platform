# Finance AI Analyst Direction

API naming: `/analysis/**` is the preferred API for new finance analyst clients. `/finance/**` is the finance-semantic alias. `/travel/**` is legacy-compatible only and should not be used by new code.

## 项目定位

本项目的长期方向是 Financial AI Analyst Platform：面向金融研究、知识学习、新闻分析、财报/研报问答、市场数据解读和风险提示的 Agentic RAG 系统。

它不是自动交易系统，不做下单执行，也不承诺收益。当前重构只改变领域语义、prompt、route alias、工具抽象和文档叙事，不改变核心 RAG、SSE、pgvector、数据库 schema 或 Agent pipeline。

## 当前状态

已具备的通用工程基础：

- `PLAN -> RETRIEVE -> TOOL -> GUARD -> WRITE` 固定 Agent workflow。
- PostgreSQL + pgvector 向量检索。
- Redis ChatMemory 和 conversation registry。
- SSE 阶段事件、policy 事件、引用片段、心跳、done/error。
- Tool governance：timeout、rate limit、circuit breaker、outcome、error_code。
- Eval harness 入口：`POST /api/v1/eval/chat`。
- JWT 鉴权、限流、Actuator、Docker Compose、Testcontainers。

本轮低风险语义重构新增：

- `FinancialAnalystAgent` 通用接口。
- `/analysis/**` 与 `/finance/**` route alias。
- `AnalysisController`、`AnalysisKnowledgeController`、`AnalysisChatRequest`。
- `GovernedAgentTool` 工具抽象。
- Mock `MarketDataTool`，不接真实金融 API。
- 金融研究向 prompt、query rewrite prompt 和 plan prompt。

## 长期目标

目标系统应支持：

- 金融知识库 RAG：财报、研报、公告、新闻、用户笔记。
- 市场数据工具：行情、历史价格、指数、宏观数据。
- 新闻与公告分析：事件时间线、影响因素、风险提示。
- 报告生成：公司分析、财报摘要、事件分析、行业周报。
- 图表解读：截图、表格、K 线或指标图的辅助理解。
- Eval/harness：事实一致性、引用覆盖、工具归因、合规拒答和时效性。

## V1

- 保留 legacy `/travel/**` API，新增 `/analysis/**` 和 `/finance/**` alias。
- Prompt 转为 Financial AI Analyst / Research Agent。
- Tool 层引入 `GovernedAgentTool` 抽象。
- 增加 mock `MarketDataTool` 验证金融工具治理链路。
- README 和 docs 改为金融研究平台叙事。
- 不修改数据库 schema、SSE 协议、pgvector、核心 pipeline。

## V2

- 增加真实但只读的数据源适配器，如新闻、财报、公告、行情查询。
- 扩展知识库 metadata 设计，但通过单独迁移计划评估 schema 变更。
- 增加金融专项 eval cases：合规拒答、引用一致性、行情时效、工具失败。
- 前端增加金融研究视图：sources、tool trace、risk notes、report history。

## V3

- 建立异步分析任务：公司深度报告、行业周报、事件追踪。
- 增加报告落库、引用审计、人工反馈闭环。
- 建立金融分析质量 dashboard。
- 支持多 provider、多模型和多 target 的 eval compare。

## 风险与边界

- 自动交易：不接券商交易 API，不生成下单动作。
- 投资建议：不输出个性化买入/卖出/持有建议。
- 收益承诺：不使用保证收益、稳赚、确定目标收益等表述。
- 内幕信息：拒绝获取、传播或利用非公开重大信息。
- 市场操纵：拒绝拉盘、砸盘、操纵舆论或规避监管请求。
- 数据时效：行情、新闻和财务数据必须尽量标注来源与时间。
- 证据不足：检索空命中、工具失败或低置信时应澄清、降级或明确不确定性。

默认声明：

```text
以下内容仅供研究和教育参考，不构成投资建议。市场有风险，决策需结合自身情况并咨询持牌专业人士。
```
