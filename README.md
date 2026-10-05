# Android 角色应用

一个以角色聊天为入口、可通过轻量 Agent 完成任务的 Android 原生应用。客户端与独立后端分开，界面与业务分开；通信、模型与工具来源可以逐步替换。

## 当前进度

阶段 6B1 已完成 Android 网络边界与恢复：业务适配器、有限重试、SSE 续接、事件/正文事务、查询回退及实时/兼容模式切换。39 项 Android 测试通过，包含内存与 HTTP 的共同契约、真实 SSE 写入 Room、断开恢复和实际笔记保存；APK 构建与静态检查通过。后端继续使用固定回复，不配置真实 AI 也可联调。持久草稿、设备交互验收、完整原型视觉、真实模型联调和 MCP 留待后续阶段。

代码仓库：私有仓库 [cn7shi/character-agent-app](https://github.com/cn7shi/character-agent-app)。按阶段提交源码、接口和设计记录，运行数据与凭证留在本机。

当前选择 Kotlin 与 Compose 构建客户端，Room 管理本地 SQLite；服务端选择 Python 与 FastAPI，开发阶段使用独立 SQLite。模型供应商、正式认证与最终产品名称尚未确定。

## 阅读入口

- [总体设计](docs/architecture.md)：两端职责、模块边界和执行归属。
- [数据模型](docs/data-model.md)：本地与服务端保存什么，如何隔离与对齐。
- [接口草案](contracts/api-v1.md)：角色、会话、任务和流式事件。
- [后端运行说明](backend/README.md)：启动、身份、当前接口与检查命令。
- [Android 运行说明](android/README.md)：构建、安装、本机连接与当前客户端范围。
- [模型配置与凭证保护](docs/model-configuration.md)：本机配置、兼容范围、Git 检查与日志处理。
- [无需 AI 的演示](docs/offline-demo.md)：两条固定回复、笔记示例与运行方法。
- [网络可靠性](docs/network-reliability.md)：适配器边界、重试、恢复及能力范围。
- [阶段报告](docs/stage-reports/06b1-network-reliability.md)：本次交付、核验和未实现范围。
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
    │   └── settings/           设置页面
    └── integrations/mcp/       可选设备侧 MCP 预留
```

每次任务明确由哪一端执行；首版在线任务在后端。客户端读取业务状态并呈现，同一数据可用于不同布局和主题。

原型参考目录为 `E:\MYcode\IOSH5DEMO`，仅作设计与实现参考，不作为新项目的运行依赖。下一步补持久草稿与设备验收，再完善原型视觉；真实 AI 可以后续配置。
