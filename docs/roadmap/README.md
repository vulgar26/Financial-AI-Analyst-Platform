# Roadmap

本目录保存仍然有效的长期演进方案。方向以 [../PRODUCT_VISION.md](../PRODUCT_VISION.md) 为准。

## 文档清单

| 文档 | 主题 |
| --- | --- |
| [finance-agent-roadmap.md](finance-agent-roadmap.md) | Finance AI Analyst 的长期架构演进方向 |
| [financial-workflows-v1.md](financial-workflows-v1.md) | 金融 workflow 场景设计（company_snapshot / market_data_explain / earnings_summary），含合规边界、工具治理、可执行切片 |
| [eval-harness-roadmap.md](eval-harness-roadmap.md) | Eval Target Adapter 与评测框架统一接口方案 |

## 已归档的设计文档

以下文档已移至 [../archive/](../archive/)，原因是超前于当前路线（先做长期价值投资、先接真实数据、不预先搭框架）或已完成，保留作历史参考：

- `finance-observer-design.md` / `finance-observer-boundaries.md` —— 标的档案 / Watchlist 设计，未来做工作台时取回参考
- `agent-platform-technical-roadmap.md` —— Agent 平台技术演进（含远期 multi-agent 等）
- `async-agent-runtime-roadmap.md` —— 异步任务 runtime 演进
- `workflow-runtime-r2-plan.md` —— runtime R2 计划（已完成）

## 共同原则

- 保留已落地的 RAG、SSE、tool governance、eval、guard/gate、Redis、PostgreSQL/pgvector 等工程基础设施。
- 先用最小闭环跑通真实价值链，再被真实需求驱动着长出框架；不先搭框架再填内容。
- 长期演进必须保持可观测、可评测、可回放、可治理。
- 金融方向定位为研究分析与信息辅助，不做自动交易、收益承诺或个性化投资建议。
