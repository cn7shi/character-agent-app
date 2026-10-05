# 阶段 6B1 补充：OpenCodeReview 委托审查与修复

日期：2026-10-05。审查基线：`5e0b8aafcc95cc5f15b6a03fa9560fb0eca30f83`。本报告和修复随同一次提交交付；不改变原 6B1 报告的历史测试数量。

## 工具、范围与覆盖

使用已安装的 Alibaba OpenCodeReview 1.12.12，按[官方委托审查技能](https://github.com/alibaba/open-code-review/blob/main/skills/open-code-review-delegate/SKILL.md)执行 `ocr delegate preview --commit 5e0b8aa --format json`，附项目背景，再对返回文件调用 `ocr delegate rule --format json` 获取规则。按 Kotlin/KTS 与 Python 两组读取差异、上下文及调用链，由 Codex 完成审查。没有为 OCR 配置独立模型 API，也没有由外部独立模型复核。

OCR 识别 32 个变更文件，其中 15 个可审查文件全部完成，跳过 0 个，覆盖率 100%（15/15）。另有 17 个文件被 OCR 排除：11 个 Markdown 的扩展名不支持、6 个测试文件命中默认路径过滤。相关测试作为复现和验证材料另外阅读、运行；这个比例不表示全仓审计或 32/32 文件审查。

逐文件状态、排除原因和结构化问题见 [JSON 报告](06b1-ocr-review.json)。原始 preview、rules 和修复前测试 XML 保存在忽略提交的 `backend/.runtime/ocr-6b1/`，不含模型配置。

## 确认的问题与处理

以下行号均指审查基线。4 项均为 medium / P2，已修复；未确认 critical / high 问题。这不是“无其他缺陷”的保证。

| 编号 | 基线位置 | 触发与影响 | 修复及证据 |
| --- | --- | --- | --- |
| F1 | ClientViewModel.kt:73–76 → 48 | 输入草稿后切实时/兼容模式，同一身份重新挂载时无条件清空草稿 | 仅真正换身份或断开时清空；切模式测试修复前实际得到空字符串，修复后保留输入 |
| F2 | ClientViewModel.kt:123–125 | 请求等待时继续输入，旧请求结束后清掉新文字；历史同文或旧待发送项也可能误作本次提交成功 | 用编辑对象身份、本次返回及新待发送 ID 判断；延迟发送用例修复前丢失新输入，修复后通过，并覆盖重新输入同文、正常清空和本地拒绝 |
| F3 | OfflineRepository.kt:152–155 | 取消已保存为终态，但旧事件流不发数据也不关闭，下一轮监听被阻塞 | Room 活动任务 ID 驱动取消和切换；修复前等待流关闭超时，修复后旧流结束且新任务从自身游标订阅 |
| F4 | ClientViewModel.kt:152 | ViewModel 退出只发出取消信号就关闭 Room，尚未完成的查询报连接池已关闭 | 统一父任务等待所有会话收尾再关闭 Room；日志曾出现未捕获 SQLite 异常，可控收尾测试也复现提前关闭，修复后通过且测试错误输出为空 |

F1/F3 与本次网络模式、事件监听的新增路径有关；F2/F4 是变更文件中原有逻辑的问题，在这次上下文审查和回归中确认，不把它们都归因于 6B1 新代码。

关键选择记录于 [D25](../decisions.md)。没有新增数据库表或修改网络协议。内部测试入口替换连接配置和后端，生产仍通过原构造入口使用 Keystore，不读取本机真实凭证。

## 实际验证

- `backend/.venv/Scripts/python.exe backend/scripts/check_android.py`：47 项通过、0 失败、0 跳过。14 项领域策略、9 项网络、14 项 Room/适配器、3 项真实本机 API/SSE、7 项 ViewModel；新增 8 项用例。
- 一键检查已包含 app 测试，避免后续只测网络和存储却漏掉界面状态。测试服务与临时数据已自动清理。
- `android/gradlew.bat :app:assembleDebug :app:lintDebug`：构建通过，Lint 0 errors / 13 warnings。比原阶段多 1 项 Robolectric 版本提示，因为 app 新增与 data 相同版本的测试依赖；其他仍为 SDK/依赖版本和 SharedPreferences KTX 提示，没有屏蔽检查。
- 提交与推送继续执行敏感信息检查，原始运行记录和构建产物保持忽略。

新增测试曾在初始化阶段等待超时，调整为先在 Robolectric 测试线程加载 Android/Room，再启动可控 UI 调度器后，才得到明确的草稿丢失断言；没有把环境初始化超时当成产品缺陷，也没有跳过失败测试。

## 限制与下一步

本次审查针对最新网络阶段及相关上下文，不是全仓后端安全审计。尚无手机或 Android 模拟器验收，测试不能代替真实输入法、Keystore 和系统后台行为。真实模型与 MCP 仍未配置或联调。继续按 6B2 推进草稿持久化和设备验证。
