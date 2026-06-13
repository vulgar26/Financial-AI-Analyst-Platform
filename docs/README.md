# finance-agent 文档

本目录只保留 6 份核心文档。历史上的 30+ 份分散文档（travel 时代规划、各阶段进度留痕、重复的 runtime 边界说明等）已于 2026-06 整合删除，需要考古时查 git 历史。

| 文档 | 看什么 |
| --- | --- |
| [VISION.md](VISION.md) | 项目定位：为什么做、给谁、第一/第二目标。**先读这份。** |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 当前真实架构：请求链路、分层、workflow runtime、工具治理、安全。 |
| [LOCAL_DEV.md](LOCAL_DEV.md) | 怎么把它跑起来、怎么 demo、常见错误排查。 |
| [EVAL.md](EVAL.md) | 评测体系：两层架构、安全用例、操作手册、CI 边界。 |
| [ROADMAP.md](ROADMAP.md) | 接下来做什么：近期砖块 + 远期愿景（含明确推迟的部分）。 |

## 阅读顺序

- **第一次接触项目**：VISION → ARCHITECTURE → LOCAL_DEV（跑起来）。
- **要改代码**：ARCHITECTURE（搞清边界）→ 对应模块。
- **碰评测**：EVAL（务必先读，两层架构容易误解为重复）。
- **想下一步做什么**：ROADMAP。

## 一个必须知道的历史背景

项目早期是「旅行 AI 助手」，后转为金融方向。**代码里大量 `travel` 命名是 legacy（如 `TravelAgent`、`com.travel.ai` 包、`/travel/**` 路由），是有意保留的兼容命名，不是 bug。** 类名改造是独立的重构任务，未来单独处理，不在文档里强行假装已改名。
