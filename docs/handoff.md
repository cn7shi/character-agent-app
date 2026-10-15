# 项目现状与交接说明

更新日期：2026-10-15。适用于开发者或后续 AI 助手接手。本文是当前状态入口，历史阶段报告保留当时结果，不随新阶段改写历史计数。

## 1. 当前停在哪里

**已完成阶段 06e 沉浸式对话流、Neural TTS 与微信式排版优化。** 当前交付是可在本机运行、已接通 Groq Qwen 27B 大模型在线推理与 Edge-TTS 神经语音合成的完整 Android 原生体验版。

| 项目 | 当前值 |
| --- | --- |
| 私有仓库 | [cn7shi/character-agent-app](https://github.com/cn7shi/character-agent-app)，默认工作分支 main；新接手者需要仓库访问权限 |
| 本机工作区 | `E:\MyProject\character-agent-app-main`；其他机器可用自己的克隆路径 |
| 最新功能提交 | `06e` 阶段成果（微信式交互、动作解析修复、Edge-TTS 语音合成与本地缓存） |
| 接口与版本 | 后端 0.6.2、Android 0.6.2-dev；新增 `/v1/tts/synthesize` 语音合成接口 |
| 数据结构 | 服务端迁移 001–005；Android Room schema 2 |
| 原型参考 | `E:\MYcode\IOSH5DEMO`，只读参考；已完成五键底部导航、广场与实验玩法的原生复刻 |
| 运行环境 | 后端 Python 3.12/3.14 + FastAPI；Android API 35 (Android 15) 模拟器 |

## 2. 已经做了什么

| 能力 | 已实现内容 | 边界 |
| --- | --- | --- |
| 五键原生导航 | 首页、广场、创作、实验玩法、消息；中间悬浮创作按键 | 已完整复刻原型布局，适配深色主题 |
| 探索广场与玩法 | 12 分类标签、热门轮播、角色双列卡片、情境剧场、TRPG 跑团掷骰 | 预设广场角色点击即聊；暂无广场在线投稿发布 |
| 沉浸式对话流 | 微信式向上滚动、输入栏固定吸底、发送消息平滑触底、气泡左右分层 | 已实现完整聊天流；暂无消息撤回与单条删除 |
| 剧本动作解析 | 重写 ReplyParser，兼容嵌套括号，提取首段引导，正文动作与对白分层，8.dp 紧凑排版 | 解决动作段落被误删与留白问题 |
| 真实模型推理 | 接入 Groq Qwen 27B (`qwen/qwen3.8-27b`)，真实在线流式交互 | 支持上下文记忆；暂未支持模型端 Function Call |
| 神经语音合成 | 集成微软 Edge-TTS，多角色音色映射，动作描写剥离，客户端 MP3 缓存与播放 | 客户端提供 VoicePlayer，离线自动降级至系统 TTS |
| 私有角色管理 | 创建、查看、编辑、删除；名称、简介、人设与笔记开关；保存后开聊 | 仅本人可见；内置角色不可编辑 |
| 个人资料 | 昵称和简介的查看、首次保存、修改、确认重置；真实落库与缓存 | 重置只清空这两项，不删除账号或其他数据 |
| 本地数据与离线 | Room 缓存、待发送记录、MessageOutbox 队列、会话创建请求、消息草稿 | 首次取数需要网络；支持断网缓存与重试 |
| 网络可靠性 | 幂等重试、超时/错误分类、SSE 续接与快照核对、查询回退、手动重试 | 基础网络故障已处理，更复杂的极端断网切流持续演进 |

## 3. 还差什么，恢复后按什么顺序做

| 优先次序 | 待交付范围 | 依赖与完成标准 |
| --- | --- | --- |
| 下一阶段：6C3 | 会话改名与搜索，然后笔记编辑与删除，逐项交付 | 先明确搜索范围、版本冲突与删除影响，再贯通 API、缓存和页面；真实落库，失败保留输入 |
| 持续补齐：6B2 | 回复进行中的设备断流续接、同一身份多会话切换、保存进行中的模式切换、中文输入法候选与光标编辑 | 按 [设备验收清单](device-acceptance.md) 留实际证据；不能用单元测试代替设备验收 |
| 真实工具调用 | 大模型真实 Function Call 适配与远程 MCP 工具发现/执行 | 需根据 Groq/OpenAI 规范完善服务端编排器 |
| 后续产品功能 | 头像/背景本地图库上传、云端同步备份、长期向量记忆 | 需逐项明确数据存储规则与云端同步策略 |
| 对外发布前 | 正式账号系统、生产环境多进程部署、用量与限流保护、发行打包与实体机验收 | 当前为开发与演示原型，对外上线需独立规划发布流程 |

Android `core/model`、`core/agent`、`core/tools`、`integrations/mcp` 以及后端 MCP 接入位置是预留边界，不是可运行能力。当前在线 Agent 只在服务端执行。PostgreSQL 是后续可评估选型，目前两端均使用各自独立的 SQLite，不能通过复制 Room 文件替代同步。

## 4. 现有验证证据

| 验证 | 最近已执行结果 | 依据与限制 |
| --- | --- | --- |
| 后端自动测试 | 70 项通过 | [6C2 报告](stage-reports/06c2-personal-profile.md)，含资料隔离、并发、冲突、重启、迁移等 |
| Android 自动测试 | 71 项通过，0 失败、0 跳过 | 包含 5 项实际连接本机 FastAPI 的 HTTP 测试；Room/ViewModel 使用 Robolectric |
| 构建 / Lint | 开发及设备测试 APK 构建通过；0 错误、14 警告 | 未关闭检查；构建通过不代替实体机测试 |
| 恢复与升级设备流程 | 历史阶段 13 个设备/主机检查点通过 | [6B2C 报告](stage-reports/06b2c-recovery-and-upgrade.md)，含强杀、取消、历史 APK 升级；本次未重跑 |
| 角色管理设备流程 | 历史阶段 2 组流程通过 | [6C1 报告](stage-reports/06c1-character-management.md)，含删除后继续旧聊天；本次未重跑 |
| 个人资料设备流程 | 最近一轮 4 个检查点通过 | [6C2 设备摘要](stage-reports/06c2-device-results.json)，轮次 `20261005T154454Z`；未注入设备网络故障 |
| OpenCodeReview | 6C2 委托审查覆盖本阶段改动 | Codex 读取 OCR 规则执行；不是另一独立模型审计，也不是全仓安全审计 |
| Git 凭证检查 | 功能提交前及推送前扫描通过 | 本次文档提交也须按相同约定检查；钩子不自动随新克隆启用 |

上述设备环境为专用 Android 15 / API 35 模拟器，不是实体手机。各阶段检查点含重复准备步骤，不能简单相加当作不重复的覆盖数量。本次交接只修改文档，没有重新运行应用测试；上表明确引用前一轮实际结果。

可提交的报告和设备摘要在仓库内；原始截图、日志、APK、临时服务数据在被忽略的 `backend/.runtime/` 或 Android 构建目录，新克隆不含这些文件。6C2 原始证据位于本机 `backend/.runtime/device-evidence-20261005T154454Z/`；需要更强交接证据时可复跑脚本，不传递开发凭证或直接打包整个运行目录。

## 5. 新接手者如何启动

先读根目录 [AGENTS.md](../AGENTS.md)，再读本文、[roadmap](roadmap.md) 和 [decisions](decisions.md)。拿到私有仓库权限后克隆，执行 `git status` 核对自己的未提交改动；不要为了对齐进度强制覆盖工作区。以下命令均从仓库根目录执行，路径可自行选择。

环境按项目固定版本准备：Windows、Python 3.14（已验证 3.14.6）、uv、Git、JDK 21、Android SDK 36 / Build Tools 36.0.0；设备检查另需可用的 adb 设备。Gradle Wrapper、Android 插件及库版本已固定，不要在接手第一步顺便升级依赖。

```powershell
git clone https://github.com/cn7shi/character-agent-app.git
Set-Location character-agent-app
git config --local core.hooksPath .githooks
uv venv backend/.venv --python 3.14
uv pip install --python backend/.venv/Scripts/python.exe -r backend/requirements-dev.txt
```

设置本机 JAVA_HOME、Android SDK 环境变量，或在忽略提交的 `android/local.properties` 配置 sdk.dir。项目不能直接复用旧机器的 `.venv`、SDK 路径或调试签名。

先不创建真实模型配置；确认本机没有环境变量把 provider 改成真实服务，默认 mock。启动后端：

```powershell
.\backend\run.ps1 -Dev
```

服务在 `127.0.0.1:8000`；保持该终端运行，另开终端构建：

```powershell
.\android\gradlew.bat -p android :app:assembleDebug :app:lintDebug
```

用 Android Studio 安装运行，或安装 `android/app/build/outputs/apk/debug/app-debug.apk`。参考 [Android 连接说明](../android/README.md#连接模拟后端) 设置 adb reverse 与地址；有多个设备时始终用 `adb -s <实际序列号>` 指定目标。本机专用 AVD 是 CharacterAgent_API35 / emulator-5580，但它不会随仓库自动创建，新环境需自己建立模拟器或选定设备；不要停止、清除其他项目的设备或数据。

首次 `-Dev` 启动在 `backend/.runtime/app/development-users.json` 生成两个开发身份。在本机读取一个凭证填入应用设置，不发送到聊天、提交 Git 或截图。应用设置接收的是本项目后端凭证，不是模型供应商密钥。默认数据为 `backend/.runtime/app/character.sqlite3`；只绑定本机，不要将开发服务直接暴露到公网。

最短手动验证：连接 → 创建自己的角色并开启笔记能力 → 开聊发送“你好” → 发送“记笔记：接手验证；资料保存” → 笔记页查看 → 我的页面保存昵称和简介 → 重开查看。重置资料前后检查角色与聊天保留。当前固定回复与真实笔记/资料落库是两种不同事实。

## 6. 接手时怎么验证

从仓库根目录运行，测试会使用隔离数据，不需要真实 AI 密钥：

```powershell
.\backend\run-tests.ps1
.\backend\.venv\Scripts\python.exe backend/scripts/check_android.py
.\android\gradlew.bat -p android :app:assembleDebug :app:lintDebug
```

Android 集成脚本负责创建和清理自己的后端。单独运行 Gradle 测试而未配置该隔离后端，会跳过 5 项真实 HTTP 测试，不能按“71 项全部执行”记录。Windows 测试需要允许本机回环连接；受限环境中阻塞时先排查执行环境，不要修改业务代码绕过测试。同一工作区不要并发运行多套 Gradle 构建。

按改动范围选择设备流程，序列号替换成自己明确选定的设备：

```powershell
# 本机已有专用 AVD 时的示例；先用 adb devices 核对。
.\backend\.venv\Scripts\python.exe backend/scripts/check_device.py --serial emulator-5580 --profiles
.\backend\.venv\Scripts\python.exe backend/scripts/check_device.py --serial emulator-5580 --characters
```

设备脚本编译并操作专用包 `com.character.app.devicecheck`，正常开发包为 `com.character.app`。各检查点共享临时数据，必须由脚本按顺序运行；不要直接随机运行全部 instrumentation 方法。基础与 `--extended` 故障/升级验收的准备步骤见 [Android 说明](../android/README.md#设备自动检查)。

提交前检查：

```powershell
git diff --check
# 仅暂存本次明确要交付的文件后运行。
.\backend\.venv\Scripts\python.exe scripts/check_secrets.py --staged
# 提交成功后、推送前检查可达历史。
.\backend\.venv\Scripts\python.exe scripts/check_secrets.py --history HEAD
```

`--history` 接收单个提交/引用，不能传 `origin/main..HEAD` 范围。推送钩子还会再次检查；失败先处理原因，不临时关闭钩子。运行记录中写明实际执行、跳过和限制。仅文档修改不必重跑整套应用测试，但需核对链接、现状和敏感信息。

## 7. 从哪些代码入口接手

| 想改的内容 | 优先阅读 |
| --- | --- |
| 服务端接口与启动组装 | `backend/src/character_app/main.py`、`api/routes.py`、`api/schemas.py` |
| 会话/角色与事务 | `application/catalog.py`、`adapters/storage/sqlite.py`；迁移只新增，不修改已应用文件 |
| 消息/任务与 Agent | `application/chat.py`、`application/agent.py`、`domain/chat.py`、`domain/tools.py` |
| 笔记功能 | `adapters/storage/tools.py`、`adapters/tools/notes.py` |
| 个人资料 | `domain/profile.py`、`application/profiles.py`、`adapters/storage/profiles.py`、迁移 005 |
| Android 状态与导航 | `android/app/src/main/kotlin/com/character/app/ClientViewModel.kt`、同目录 `MainActivity.kt` |
| 可拔插业务协议 | `android/core/domain/src/main/kotlin/com/character/app/domain/Models.kt`、`Reliability.kt`、`RunMonitor.kt` |
| HTTP 与本地数据 | `core/data` 下 HttpBackend、JsonCodec、OfflineRepository、ProfileCache、ClientDatabase；`core/network` 下 Transport、SseReader |
| 页面与视觉 | `android/feature/*`；主题在 `core/designsystem`，页面不要直接连 SQL 或模型 |
| 自动验证 | `backend/tests`、Android 各模块 `src/test`、`app/src/androidTest`、`backend/scripts/check_android.py` / `check_device.py` |

表中省略前缀的后端路径均相对于 `backend/src/character_app/`；Android 类可按类名定位到对应模块源码。接口变更后重新导出 OpenAPI，并同步 [接口约定](../contracts/api-v1.md)。

不要改坏的约定：服务端数据按认证身份隔离；旧会话引用不可变角色版本；消息、事件序号与任务状态原子保存；未知结果使用原请求身份重试；角色与资料提交携带版本；资料重置保留递增版本；停止不撤销已提交笔记；界面和数据库、模型供应商保持分离。细节与代价见 D18–D30 等 [决策记录](decisions.md)。

## 8. 恢复开发时的交付方式

收到用户继续指示后，先核对 main 最新状态与未提交改动，再按 roadmap 选一个小阶段。默认先做 6C3 的会话改名/搜索，避免一口气加入全部待办；优先可操作功能，必要验证随功能完成，6B2 剩余验收不作为所有功能的前置条件。

每阶段做到：实现两端完整行为、验证关键失败场景、在代码旁或 decisions 记录原因、在 stage-reports 记录实际结果与限制、更新本文/roadmap/README，再扫描敏感信息并按已有 Git 协作约定提交。需要外部服务或正式发布的步骤另按当时用户授权执行。本次暂停不创建定时任务，也不自动继续后续开发。
