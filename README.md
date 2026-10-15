# Android 角色应用

一个以角色聊天为入口、可通过轻量 Agent 完成任务的 Android 原生应用。客户端与独立后端分开，界面与业务分开；通信、模型与工具来源可以逐步替换。

## 当前进度

截至 2026-10-15，项目已完成 **06e 沉浸式对话流、Neural TTS 与微信式排版优化**。接手请先阅读 [项目现状与交接说明](docs/handoff.md)。

当前阶段核心成果：
1. **五键导航与页面复刻**：完成首页、广场、创作、实验玩法、消息五个核心页面，中间保留悬浮创作按键。
2. **微信式对话交互**：重构对话流为 `LazyColumn` 布局，输入框固定吸底，发送消息平滑滚动至最新气泡。
3. **剧本动作解析修复**：重写 `ReplyParser`，支持嵌套括号识别，完整保留剧情叙事动作，段间距统一设为 8.dp。
4. **神经语音合成接入**：服务端集成 Edge-TTS 引擎，提供分角色音色定制与动作剥离；客户端实现 MP3 缓存与播放。
5. **真实模型联调**：后端接入 Groq Qwen 27B 在线推理，通过 Android 15 (API 35) 模拟器实机验证。

代码仓库：私有仓库 [cn7shi/character-agent-app](https://github.com/cn7shi/character-agent-app)。按阶段提交源码、接口和设计记录，运行数据与凭据留在本机。

客户端采用 Kotlin 与 Compose 构建，使用 Room 管理本地 SQLite；服务端采用 Python 与 FastAPI，配合独立 SQLite 存储。当前默认模型接入 Groq Qwen 27B，语音引擎接入 Edge-TTS。

## 阅读入口

- [项目现状与交接说明](docs/handoff.md)：当前停点、完整功能清单、缺口与接手步骤。
- [最新功能阶段报告 (06e 沉浸对话与语音)](docs/stage-reports/06e-immersive-dialogue-and-tts.md)：微信式交互、动作解析修复与 Neural TTS 落地。
- [探索广场阶段报告 (06d 广场与导航)](docs/stage-reports/06d-discovery-plaza-and-navigation.md)：探索广场、实验玩法与五键导航复刻。
- [总体设计](docs/architecture.md)：两端职责、模块边界和执行归属。
- [数据模型](docs/data-model.md)：本地与服务端保存什么，如何隔离与对齐。
- [接口草案](contracts/api-v1.md)：角色、会话、任务和流式事件。
- [后端运行说明](backend/README.md)：启动、身份、当前接口与检查命令。
- [Android 运行说明](android/README.md)：构建、安装、本机连接与当前客户端范围。
- [模型配置与凭证保护](docs/model-configuration.md)：本机配置、兼容范围、Git 检查与日志处理。
- [无需 AI 的演示](docs/offline-demo.md)：两条固定回复、笔记示例与运行方法。
- [网络可靠性](docs/network-reliability.md)：适配器边界、重试、恢复及能力范围。
- [设备验收清单](docs/device-acceptance.md)：已验收和仍须验证的具体场景。
- [设计决策记录](docs/decisions.md)：为什么这样写、取舍与未来调整条件。
- [已实现接口](contracts/openapi-v1.json)：从当前代码生成的 OpenAPI。
- [推进计划](docs/roadmap.md)：每一步的范围与验收条件。

## 项目结构

```text
Company/
├── README.md
├── docs/                       架构、数据模型、计划与阶段报告
├── contracts/                  跨端 API 草案与协议样例
├── backend/
│   ├── README.md
│   ├── migrations/             数据库迁移
│   └── src/character_app/
│       ├── api/                请求、身份与响应适配
│       ├── domain/             业务实体、状态与接口
│       ├── application/        业务操作与 Agent 编排
│       └── adapters/           数据库、模型、工具与 MCP
└── android/
    ├── app/                    启动与模块组装
    ├── core/
    │   ├── domain/             客户端业务核心
    │   ├── data/               Room、远端数据与对齐
    │   ├── network/            可替换通信实现
    │   ├── model/              可选本地或直连模型预留
    │   ├── agent/              轻量协调与本地任务预留
    │   ├── tools/              设备工具预留
    │   └── designsystem/       主题与共享组件
    ├── feature/
    │   ├── characters/         角色页面
    │   ├── chat/               聊天页面
    │   ├── notes/              笔记页面
    │   ├── profile/            个人资料查看、编辑与重置
    │   └── settings/           设置页面
    └── integrations/mcp/       可选设备侧 MCP 预留
```

每次任务明确由哪一端执行；首版在线任务在后端。客户端读取业务状态并呈现，同一数据可用于不同布局和主题。

原型参考目录为 `E:\MYcode\IOSH5DEMO`，仅作设计与实现参考，不作为新项目的运行依赖。按用户反馈优先推进可操作的小功能，再完善原型视觉；真实 AI 可以后续配置。
