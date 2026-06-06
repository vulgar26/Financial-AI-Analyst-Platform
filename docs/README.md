# 文档导航

本目录保留当前有效的文档。阶段记录、历史计划、超前设计和周报模板已归档到 [archive/](archive/)。

> **先读 [PRODUCT_VISION.md](PRODUCT_VISION.md)** —— 项目定位与路线图的北极星文档。
> 项目方向已重定为「长期价值投资研究工作台」（帮你想清楚决策、把投资原则变成可验证规则、对话只是入口之一）。

## 推荐阅读顺序

| 文档 | 作用 |
| --- | --- |
| [PRODUCT_VISION.md](PRODUCT_VISION.md) | **定位、形态、路线图（北极星，冲突时以此为准）** |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 核心请求链路、Agent 阶段、SSE、鉴权、限流、超时与 Compose 说明 |
| [LOCAL_DEV.md](LOCAL_DEV.md) | 本地启动与常见排错 |
| [demo.md](demo.md) | 手动演示流程和 curl 示例 |
| [FRONTEND_DEMO.md](FRONTEND_DEMO.md) | 前端展示页能力、接口清单和手动验收步骤 |
| [eval.md](eval.md) | 评测接口、RAG/工具/安全样例和回归验证入口 |
| [eval/SOURCES_EVAL_VS_SSE.md](eval/SOURCES_EVAL_VS_SSE.md) | eval `sources[]` 与 SSE 引用片段的证据口径说明 |
| [TOOL_GOVERNANCE_SPEC.md](TOOL_GOVERNANCE_SPEC.md) | 工具调用归因、超时、限流、熔断和观测字段约定 |
| [ACTUATOR_HEALTH_BASICS.md](ACTUATOR_HEALTH_BASICS.md) | Actuator 健康检查和探活基础说明 |
| [FINANCE_AI_ANALYST.md](FINANCE_AI_ANALYST.md) | 金融分析平台方向、合规边界和非投资建议声明 |
| [roadmap/](roadmap/) | Finance Agent 演进与金融 workflow 场景设计 |
| [architecture/](architecture/) | Agent / Runtime / Eval 的架构边界与拆解进展 |

## 归档内容

[archive/](archive/) 保留开发过程材料和超前设计，用于追溯，不作为当前主叙事：

- 阶段总结、Day/P0/P1 记录、eval harness 历史 gap/阈值/断点恢复说明。
- 升级计划与历史实现对照（含 travel-ai 时代的原始规划）。
- 超前的设计文档：finance-observer（标的档案/Watchlist 设计，未来做时取回参考）、
  agent-platform 技术演进、异步 runtime、workflow-runtime R2 计划（已完成）。

当前能力以 [PRODUCT_VISION.md](PRODUCT_VISION.md)、根目录 [README.md](../README.md) 和源码为准。
