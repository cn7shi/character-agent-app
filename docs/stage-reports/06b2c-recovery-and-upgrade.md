# 阶段 6B2C：前台强杀、部分回复取消与历史 APK 升级

日期：2026-10-05。本阶段补齐三类恢复验证，13 个设备/主机检查点全部通过，没有修改应用业务源码。完整阶段 6B2 仍有待验场景，见 [设备清单](../device-acceptance.md)。

## 实际结果

| 场景 | 实际操作和证据 | 结果 |
| --- | --- | --- |
| 前台突然强杀 | 草稿落库后核对当前 PID、RESUMED Activity 和系统前台记录；在测试仍等待、退出清理尚未执行时强制停止。主机确认进程消失、没有正常 JUnit 结束或退出见证文件，再启动检查 | 中文、换行和表情草稿完整恢复；服务端消息集合不变，没有自动发送 |
| 部分输出停止 | 真实笔记工具先提交，再让模拟模型在第一段正文后暂停；通过界面点停止并发送下一条 | 旧任务为 cancelled 且有部分正文；新增笔记仅一份；按新消息 ID 核对下一任务 completed，新草稿保留 |
| 真实旧版覆盖升级 | 从历史提交构建 schema 1 APK，旧版经设置和聊天页面保存消息及离线 outbox；直接覆盖安装当前 schema 2 APK | snapshots、outbox、conversation_requests 逐行一致，加密连接保留；升级后写入草稿并重开恢复；恢复服务后原 ID/键重放返回 200，任务完成 |
| 原有六组场景 | 本轮重新运行连接、发送、草稿、清空、笔记、主题、模式、身份、停服缓存和原请求恢复 | 全部通过 |
| 清理 | 删除临时配置和状态文件、关闭本轮服务、移除本轮转发、停止测试包 | 通过，摘要中 cleanup_complete 为 true |

13 个检查点包括 12 项正常结束的 Android JUnit，以及 1 项刻意被强杀、由主机验证的检查点；不能描述成 13 项 JUnit 都正常结束。部分回复场景是在已提交笔记之后取消，没有宣称撤销已完成的工具操作。

结束后另行核对专用模拟器身份，确认无本轮端口转发、私有目录只剩截图及标记等非凭证文件，再关闭该模拟器。

## 环境与证据

- 独立 AVD `CharacterAgent_API35`，Android 15 / API 35 / x86_64 / 1080×2340；Windows、JDK 21、Python 3.14.6。
- 使用隔离包 `com.character.app.devicecheck` 和临时 mock 服务；不读取真实模型凭证，不清除正常开发包的数据。
- 当前应用 APK 的 SHA-256 与 6B2B 相同，生产源码没有改变；测试包和主机脚本有修改。构建基线为 `b46c12f154dc9fd18b2a0b1dd0a1a799af2aa5d0` 加本阶段未提交修改，不把基线当成最终测试源码提交。
- 历史版本来自 `b1f27ae6d91ad444ced71ef85a80a148e66ca2e1`；唯一源码覆盖为专用 applicationId，使用同一本机调试签名。历史 APK 哈希与两份当前 APK 哈希均写入 [结果 JSON](06b2c-device-results.json)。
- 最终运行记录在本机忽略目录 `backend/.runtime/device-evidence-20261005T143945Z/`，共 12 张截图和逐项输出。已查看强杀恢复、取消后继续、旧版待发送、覆盖后草稿和恢复后聊天截图；未截取凭证页。可提交 JSON 与本机最终摘要一致。
- 结果里的 persisted_notes=1 对应基础六组完成时；notes_after_cancellation=2 对应新增取消场景后。旧版升级另用独立后端数据，两个阶段不混算。

旧版准备数据之前清空的是脚本拥有的专用测试包。schema 1 → 2 的实际升级中没有清库、卸载、SQL 注入或替换数据库。快照对比等待缓存角色出现，确保 Room 已打开并完成迁移。

## 新增手段及自查修正

新增 `build_device_baseline.py` 从固定 Git 提交导出并构建历史包，产物和源码均留在忽略目录；扩展 `check_device.py --extended` 编排强杀、取消和升级。测试服务通过独立的 `device_fixture.py` 注入暂停逻辑，生产工厂入口保持原行为。复跑方式见 [Android 说明](../../android/README.md#设备自动检查)，设计原因见 [D28](../decisions.md#d28-让故障发生在可核对的时刻并用历史安装包验证升级)。

本阶段处理了三个测试可靠性问题：

1. 首轮在读取旧版测试身份 JSON 时使用 Windows 默认 GBK，中文内容导致解码失败。改为明确 UTF-8；该失败轮次仍保留在 `device-evidence-20261005T143401Z/`，当轮已经通过的强杀/取消不能替代整轮成功。最终完整重跑通过。
2. 升级检查原先等待静态标题，标题可能先于 Room 迁移显示。改为等待旧缓存角色出现，再核对 schema 和旧数据，避免把尚未打开数据库误判为迁移失败。
3. 固定回复会在历史记录中重复，单纯找到相同文字不足以证明停止后的新回复完成。新增主机按新消息 ID 和任务终态核对，避免旧气泡造成误报。

## 本轮验证与边界

| 验证 | 本轮结果 |
| --- | --- |
| 历史 schema 1 APK 构建 | 通过 |
| 当前 deviceCheck APK 与 instrumentation APK 构建 | 通过 |
| 扩展设备/主机检查 | 13/13 通过，配置/转发清理通过 |
| 默认测试服务 HTTP / SSE / 重启 | 重新通过；含身份隔离、断流续接、取消、中断恢复、原键重试与笔记去重 |
| Android 本机测试 / 后端业务测试 / Lint | 本轮没有改业务源码或构建依赖，不重复运行；前阶段基线为 61 项 / 57 项通过、Lint 0 错误 14 警告，不能算成本轮重新执行 |

设备侧回复进行中的断流续接、同一身份多会话切换、保存进行中的模式切换、中文输入法候选及光标编辑仍未完成。主机 HTTP/SSE 续接通过不等于这些设备场景已验收。强制停止不代表低内存回收或断电；调试签名的固定旧版升级不代表应用商店或所有历史版本。实体手机、真实模型和 MCP 尚未验证。

## OpenCodeReview 委托复查

使用已安装的 OpenCodeReview 1.12.12，通过 `delegate preview` 和 `delegate rule` 取得范围与 Kotlin/Python/JSON 规则，由 Codex 执行复查。没有配置独立模型，也没有声称经过另一模型独立审查。记录保存在忽略目录 `backend/.runtime/ocr-6b2c/`。

最终范围 12 个文件：6 个可审查文件全部审查，0 个跳过；6 个 Markdown 被默认扩展名规则排除，仍人工核对设计原因、实际结果和未验收范围。

| 文件 | 复查重点 | 结论 |
| --- | --- | --- |
| DeviceAcceptanceTest.kt | 前台进程证据、部分正文、旧库真实来源、异步迁移等待 | 迁移等待修正；设备实跑通过 |
| check_device.py | 专用包保护、凭证隔离、原请求身份、编码、误报和清理 | UTF-8 与新任务核验修正；完整重跑通过 |
| build_device_baseline.py | 固定历史提交、路径边界、唯一覆盖及 APK 哈希 | 已审查并构建真实历史包 |
| device_fixture.py | 工具实际提交顺序、暂停可取消、测试代码隔离 | 已审查，取消场景通过 |
| smoke_server.py | 显式选择测试工厂，默认服务不变，资源清理 | 已审查，默认真实 HTTP 检查通过 |
| 06b2c-device-results.json | 实跑摘要一致性、失败/成功和凭证排除 | 已与原始最终结果核对 |

确认并修正的问题记录如下，行号对应本阶段代码：

```json
[
  {"path":"backend/scripts/check_device.py","start_line":245,"category":"bug","severity":"medium","status":"fixed","content":"Windows 默认编码不能读取含中文的 UTF-8 身份 JSON，需显式指定编码。"},
  {"path":"android/app/src/androidTest/kotlin/com/character/app/DeviceAcceptanceTest.kt","start_line":320,"end_line":323,"category":"bug","severity":"medium","status":"fixed","content":"静态标题出现不能证明 Room 迁移结束，改为等待旧缓存角色加载。"},
  {"path":"backend/scripts/check_device.py","start_line":222,"end_line":229,"category":"bug","severity":"medium","status":"fixed","content":"重复的模拟回复气泡不能证明新任务完成，补充新消息 ID 与终态核验。"}
]
```

结论限于本阶段改动及已执行场景，不代表全仓安全审计。下一步继续设备断流和切换场景，然后进入原型与多主题。
