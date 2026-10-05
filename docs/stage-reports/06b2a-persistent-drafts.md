# 阶段 6B2A：本地草稿与数据库升级

日期：2026-10-05。基于 `b1f27ae`，继续原生客户端的小步开发，不依赖真实 AI。6B2 的草稿部分完成，设备验收仍未完成。

## 交付

- 草稿存入 Room，按连接身份和会话隔离，保留原始空格、换行和表情。清空输入也持久保存；离线读取不等待网络结果。
- 输入区显示正在保存、已保存在此设备或保存失败后的重试入口。首次恢复期间禁用输入，避免未读取完就覆盖旧草稿。
- 快速输入合并为最新待写内容；切换连接不取消已经接收的本地保存。普通 ViewModel 销毁会排空本地写入，再关闭数据库。
- 草稿与发送队列在同一事务交接，只有对应编辑版本被消费。发送途中继续输入、重新输入相同文本、取消后切换连接模式，都不会让旧结果清掉新稿或把已提交内容恢复成旧稿。
- schema 1 → 2 增量迁移，只新增草稿表，保留消息、任务游标、待发送消息和会话创建请求键。schema 1、2 都纳入版本管理。
- [D26](../decisions.md) 记录为何采用编辑版本、顺序写入、原子交接和非破坏性迁移。

## 实际验证

`backend/.venv/Scripts/python.exe backend/scripts/check_android.py`：61 项通过，0 失败、0 跳过；比上一阶段新增 14 项。包含 14 项领域策略、9 项网络、19 项 Room/适配器、1 项旧库迁移、3 项真实本机 HTTP/SSE 和 15 项 ViewModel 测试。隔离模拟后端和临时数据均已清理。

`android/gradlew.bat -p android :app:assembleDebug :app:lintDebug`：构建通过，Lint 0 errors / 13 warnings，数量与上一阶段相同，仍为依赖/SDK 版本及 SharedPreferences KTX 提示；未屏蔽检查。运行数据库、OCR 原始产物和 APK 继续忽略提交，提交和推送沿用敏感信息检查钩子。

新增回归覆盖：离线/跨会话重建恢复、空白和表情保留、连续 100 次编辑后立即销毁、发送等待时保存下一条、删除草稿后重建、已提交草稿不再恢复、切换身份后恢复各自内容、存储失败重试、重连后失败提示、模式切换途中已提交内容不复活、发送校验失败、交接事务回滚、响应丢失沿原请求恢复及 v1 数据升级。

迁移测试从受版本管理的 `1.json` 生成真正的 SQLite 旧库，插入旧任务/消息、未确认 outbox 和会话创建请求，再用生产 `ClientDatabase` 打开升级。明确断言旧正文、游标、错误状态和请求键保留，并验证新草稿可写可读；没有仅用空库冒充迁移验收。

开发自查复现了“发送过程中切换模式后，旧内存草稿再次出现”的边界，补上 `switchingModeDuringSendDoesNotRestoreConsumedDraft`；最终实现每次打开会话核对本地状态，并按编辑版本保护更新。

## OCR 委托复查

继续使用 OpenCodeReview 1.12.12 的 `delegate preview` 与 `delegate rule`，由 Codex 应用返回的 Kotlin/KTS 与 JSON 规则。未配置额外模型 API，也没有独立模型复审。

代码完成、补写阶段文档前的工作区快照共 12 个文件：可审查 9、已审查 9、跳过 0，覆盖率 100%。3 个测试文件被 OCR 默认路径过滤，仍作为验证材料阅读和运行；后续阶段文档不计入这次代码快照。原始范围与规则保存在忽略提交的 `backend/.runtime/ocr-6b2/`。

| 文件（相对仓库） | 状态 | 复查重点 |
| --- | --- | --- |
| android/app/src/main/kotlin/com/character/app/ClientViewModel.kt | 已审查 | 编辑版本、读取/发送竞态、身份、保存错误与关闭顺序 |
| android/app/src/main/kotlin/com/character/app/MainActivity.kt | 已审查 | 草稿状态及重试回调传递 |
| android/app/src/main/kotlin/com/character/app/DraftWriter.kt | 已审查 | 合并写入、发送前等待、失败重试和退出排空 |
| android/core/data/build.gradle.kts | 已审查 | 测试使用仓库 schema，未更换依赖 |
| android/core/data/src/main/kotlin/com/character/app/data/ClientDatabase.kt | 已审查 | 复合主键、条件删除、自动迁移与旧 schema |
| android/core/data/src/main/kotlin/com/character/app/data/OfflineRepository.kt | 已审查 | 草稿与 outbox 事务，输入校验不丢原文 |
| android/core/domain/src/main/kotlin/com/character/app/domain/Models.kt | 已审查 | 纯 Kotlin 草稿与仓库边界 |
| android/feature/chat/src/main/kotlin/com/character/app/chat/ChatScreen.kt | 已审查 | 恢复期间编辑限制、保存提示和可用重试入口 |
| android/core/data/schemas/com.character.app.data.ClientDatabase/2.json | 已审查 | 生成字段/键，旧三张表结构保持一致 |

复查发现并修复 1 项 medium / bug：保存失败后断开再连接，内存已清空，恢复旧正文时若显示“已保存”会隐藏重试入口，而发送又被此前失败状态拦住。现在保留失败状态、说明恢复的是最近保存版本，并提供重试；回归为 `failedSaveAfterReconnectStillOffersRetry`。结构化记录：

```json
{"path":"android/app/src/main/kotlin/com/character/app/ClientViewModel.kt","start_line":145,"end_line":150,"category":"bug","severity":"medium","status":"fixed","content":"保存失败后重连恢复旧稿时仍需展示失败状态及保存重试入口。"}
```

没有遗留已确认的阻断问题；这一结论限于本次变更，不代表全仓安全审计或设备行为验收。

## 限制和下一步

本机设备检查没有发现已连接手机，模拟器列表为空，因此没有执行真机/模拟器安装、输入法、Keystore、系统强杀或系统断网测试。Robolectric 的文件数据库重开与 ViewModel 重建属于本机模拟验证，不冒充真实进程强杀。下一步依 [设备验收清单](../device-acceptance.md) 完成 6B2 的剩余部分，然后进入原型与主题。

已落库的草稿可恢复；尚显示“保存中”的最后修改遇到强杀或断电仍可能丢失。草稿不上传、不跨设备同步，卸载/清除应用数据不会保留。真实模型与 MCP 继续后置。
