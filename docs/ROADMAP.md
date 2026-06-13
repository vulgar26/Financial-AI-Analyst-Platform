# ROADMAP — 接下来做什么

> 按 [VISION](VISION.md) 的判据排序：优先**技术含量/简历价值**，金融认知是副产品。
> 原则仍然有效：**别先搭空框架**（最大历史陷阱）。想做高级的，就用成熟框架最小落地、真跑起来。

## 已落地（简历可写）

- ✅ `PLAN→RETRIEVE→TOOL→GUARD→WRITE` 固定线性链路 + 可解释 SSE。
- ✅ 轻量 workflow runtime（`LinearWorkflowRuntime` + StageNode + trace，feature flag 灰度）。
- ✅ 工具治理（超时/限流/熔断/降级/归因码）。
- ✅ 真实美股基本面数据接入（FMP，含缓存/降级）。
- ✅ RAG 用 Spring AI 官方 PgVectorStore（按 user_id 隔离）。
- ✅ chat 主模型换 Anthropic Claude（供应商中立抽象）。
- ✅ eval 两层评测体系（离线确定性 + 主线真实链路）+ CI（Testcontainers + 契约测试）。
- ✅ 鉴权/会话隔离、Actuator 探活、Docker Compose 部署。

## 近期砖块（被需求逼出，独立可用、可验证）

### 砖块 A：基于真实基本面的结构化解读
让 LLM 基于已接入的真实基本面数据，给出结构化解读（估值高低、盈利质量、负债风险），带数据时点和来源。对应下方 `company_snapshot` / `market_data_explain` 场景。**这是把「接了数据」变成「数据真的被用起来」的一步，demo 效果最直观。**

### 砖块 B：RAG 编排换 Spring AI 模块化（简历核心候选）
当前 RAG 编排（QueryRewriter 改写、多路检索合并去重）是自研。Spring AI 提供模块化 RAG（`RewriteQueryTransformer`、`MultiQueryExpander`、`RetrievalAugmentationAdvisor`、`ContextualQueryAugmenter`、`ConcatenationDocumentJoiner` 等）。
- **价值**：简历上「用 Spring AI 模块化 RAG 重构了自研检索编排」是面试硬货。
- **判据下结论**：该换（净加分）。唯一要留意的是版本/API 稳定性——落地时核对当前 Spring AI 版本的模块化 RAG API 是否 GA。
- 注意：embedding 当前是 DashScope 且无 key 未真跑，换 RAG 编排前需先让 embedding 能真跑（否则验证不了效果）。

### 砖块 C：历史财务数据 + 历史分位
接入历史财务数据，回答「这只票 PE 在它过去 5 年处于什么分位」。让分析从「单点」变「有参照」。

## 三个金融 workflow 场景（设计已就绪，可逐个编码）

映射到现有 pipeline，不引入新框架。共同边界：标注来源/时点；检索空/工具失败时降级不编造。

| 场景 | 目标 | 输出 |
| --- | --- | --- |
| `company_snapshot` | 公司概览/研究快照 | 带来源和风险提示的研究草稿 |
| `market_data_explain` | 行情/指标解释 | 带时效标注的指标解释 + 可能影响因素 |
| `earnings_summary` | 财报/公告摘要 | 关键变化 + 待核实事项 |

pipeline 映射：`PLAN` 选 workflow + 声明所需证据/工具 → `RETRIEVE` 查可引用证据 → `TOOL` 拉只读数据 → `GUARD` 查边界/时效/空检索/注入 → `WRITE` 出带引用和不确定性标注的草稿。

**最小 demo 建议**：`market_data_explain` 优先（基本面数据已接入，不需要更多外部依赖），是把现有零件串成一个完整可演示 workflow 的最短路径。

## 远期愿景（明确推迟，不现在做）

这些是方向，不是当前任务。**绝不先搭框架，等真实需求逼出来再做。**

- **Finance Observer / 主动巡检**：Watchlist（关注列表）+ 定时抓数据/新闻 + 主动生成观察报告。设计草案（DB schema、API contract、D1~D7 分期）在 git 历史的 `finance-observer-design.md` / `finance-observer-boundaries.md`，要做时取出来重判（注意旧草案是「只读不给建议」的保守人格，与新定位不符的部分要按 VISION 重写）。
- **异步 Agent Runtime**：Job lifecycle、Checkpoint、Worker pool、Event store。当前系统以单轮 SSE 为边界，异步框架仅有骨架（`AgentTaskWorker`，mock）。设计见 git 历史 `async-agent-runtime-roadmap.md`。
- **多 workflow / 多 agent**：runtime 的 `workflow_id`/`workflow_family` 是为此预留的种子。等 trace/policy/tool trace 稳定后再评估。**不提前引入 DAG/MQ/Temporal/通用 agent framework。**
- 其它：A 股/基金/宏观/新闻情绪、PDF 研报解析、回测（充满幸存者/前视/过拟合陷阱，最易做死，放最后）。

## 合规边界（角色已变，仍保留为良好实践）

项目确认是**自用 + 简历项目**，所以合规不再是「砍功能」的设计驱动力（那是给「对外产品」的）。但作为工程上的良好实践、也作为 GUARD 阶段的真实可观测语义，保留以下：

- 内容研究/教育用途，输出标注非投资建议、不执行交易、需人工复核。
- 行情/财报尽量标注来源和时点；mock/非实时数据显式标注。
- 检索空/工具失败/证据不足时降级说明，不编造。

金融 GUARD 结构化归因码（落地时复用）：`FINANCE_INVESTMENT_ADVICE_BLOCKED`、`FINANCE_MOCK_DATA_DISCLOSURE_REQUIRED`、`FINANCE_EVIDENCE_INSUFFICIENT`、`FINANCE_ENTITY_AMBIGUOUS`、`FINANCE_TOOL_UNAVAILABLE`、`FINANCE_ACCOUNTING_LEGAL_BOUNDARY`、`FINANCE_FRESHNESS_UNKNOWN`。

> 注：旧定位里「不主动选股、不给买卖点提示」是因为担责/合规。自用前提下这些**不再是禁区**，但「买卖点提示」按 VISION 判据仍不做——理由变成「技术上没含量、你也判断不了对错」，而非「合规不允许」。
