# Android 原生客户端

已建立 Kotlin/Compose 多模块工程、Room 缓存与待发送记录、HTTP/SSE 后端适配，以及角色、会话、聊天、笔记和连接设置页面。默认连接现有模拟后端，不需要真实 AI；首次加载与发送仍需要本机后端运行。

这是一份开发版客户端。当前没有正式登录、后台自动发送或原型级视觉还原。网络恢复、本地草稿与数据库升级已完成本机测试，设备验收仍待进行。实际验证见 [阶段报告](../docs/stage-reports/06b2a-persistent-drafts.md)，策略详见 [网络可靠性](../docs/network-reliability.md)。

## 构建

已固定 Gradle 9.5.0、AGP 9.3.2、Kotlin/Compose 编译插件 2.2.10、Compose BOM 2026.02.01、Room 2.8.5、KSP 2.3.6。使用 JDK 21、Android SDK 36 与 Build Tools 36.0.0，最低 Android 8.0（API 26）。Gradle 下载包含官方 SHA-256 校验。

在 Android Studio 打开本目录。首次使用在忽略提交的 `local.properties` 配置 `sdk.dir`，或通过本机 Android SDK 环境变量指定目录；不要把机器路径提交到 Git。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

安装包生成在 `app/build/outputs/apk/debug/app-debug.apk`。构建、缓存与安装包均忽略提交；源码与 Room schema 入库。没有打包任何模型密钥或开发访问凭证。

## 连接模拟后端

1. 在项目根目录运行 `backend/run.ps1 -Dev`，模型保持 `mock`。
2. USB 调试手机连接电脑后，执行 `adb reverse tcp:8000 tcp:8000`，应用地址填写 `http://127.0.0.1:8000/`。Android 模拟器也可以使用 `http://10.0.2.2:8000/`。
3. 在本机查看被忽略的 `backend/.runtime/app/development-users.json`，将其中一个用户的访问凭证填入应用设置。不要把这个文件、内容或带凭证截图提交或发送到聊天中。
4. 保存连接后，刷新角色列表，选择林舟开启新会话。发送“你好”“今天有点累”或“记笔记：周末安排；买咖啡；看电影”。笔记页可查看实际保存结果。

后端继续只监听回环地址，不需要为了手机联调改成对外监听。只有调试构建对上述两个地址开放明文 HTTP，其他地址要求 HTTPS；不跟随重定向。当前后端开发身份仍不适合对外部署。

应用设置中的凭证属于本项目后端，不能填写 AI 供应商密钥。连接信息用 Android Keystore 的 AES-GCM 密钥加密保存，应用禁用备份；更改连接会切换缓存范围，断开会移除连接凭证并隐藏原缓存。缓存范围按地址与凭证指纹划分，凭证轮换会生成新的缓存范围，正式账号接入时改用经过验证的稳定账号标识。

## 当前交互与恢复

- 角色列表、会话、已获取消息和笔记写入 Room，离线可查看。列表会读完后端分页并按 ID 合并；当前面向小规模开发数据。
- 发送前保存消息标识、请求键和确认状态。可恢复故障自动尝试最多 3 次，总时限 25 秒；失败后保留待发送项供手动重试，不会换新键重新生成。
- 首次明确拒绝受理的请求可以从待发送区移除。发生过结果不明的请求必须用原键核对，即使后续遇到拒绝也不能据此认定第一次没成功。
- 创建会话也保存请求键，响应丢失后再次点击同一角色会继续原创建操作。
- 默认实时订阅当前任务，重复事件忽略，断流核对快照后从已提交序号恢复；最多 3 次流连接后回退每秒查询。设置页可直接开启“使用兼容连接”，模式切换保留原缓存。
- 查询重试耗尽会停止观察并显示“重新连接”。返回列表或切后台取消观察套接字与等待，前台重新核对；只点“停止”才取消服务端任务。
- 任务、消息与恢复序号在同一 Room 事务保存，较旧或相同序号不能覆盖新状态，终态保持不变。重新进入会话查询持久记录，不重新创建已受理任务。
- 深浅色切换只替换颜色，业务数据和任务不变。完整视觉主题、第二种聊天布局在阶段 7 实施。

草稿按连接范围和会话写入 Room，保留原始空格、换行和表情。已保存内容可在离线或重开后恢复，清空输入也会保存；输入区区分保存中、已保存和失败重试。发送时对应编辑版本与 outbox 原子交接，旧发送结果不会清掉后续输入。普通退出排空本地保存；强杀/断电时尚未完成保存的最后编辑仍可能丢失。断开后隐藏原草稿，重连同一身份可恢复已保存内容；草稿不上传或跨设备同步。

数据库从 schema 1 自动增量升级到 2，只新增草稿表，不删除消息或待发送记录；没有破坏性重建。尚无离线创建会话、消息编辑、笔记修改/删除、缓存清理与正式账号切换流程。

## 验证

在项目根目录运行：

```powershell
backend/.venv/Scripts/python.exe backend/scripts/check_android.py
```

脚本创建隔离的本机模拟后端，运行 Kotlin 策略、网络、Room、迁移与 ViewModel 共 61 项测试。内存模拟和两种 HTTP 模式运行同一业务契约，真实 SSE 直接更新 Room，并发送两次聊天和一次笔记指令，核验落库、重开数据库和用户隔离；另覆盖草稿恢复、保存错误、发送交接回滚、旧库升级、模式切换、取消后的新任务监听及退出关库时序。凭证只从临时文件读取，不写源码、命令参数或日志；结束后清理测试服务和数据。需要本机网络、JDK/SDK、Python 开发环境及 Gradle 依赖下载权限。

单独运行 `:core:domain:test :core:network:test :core:data:testDebugUnitTest :app:testDebugUnitTest` 时，不配置隔离后端的 3 项真实 HTTP 用例会跳过。Room 与 ViewModel 测试采用 Robolectric（API 34），不能代替手机上的 UI、输入法、Keystore 与生命周期验收。草稿复查见 [最新报告](../docs/stage-reports/06b2a-persistent-drafts.md)，网络阶段问题见 [前次 OCR 报告](../docs/stage-reports/06b1-ocr-review.md)；设备准备就绪后按 [验收清单](../docs/device-acceptance.md) 执行。

## 模块边界

`app` 负责依赖组装、导航、连接保存和 ViewModel；四个 `feature` 模块只读业务模型并发出操作回调。`core/domain` 不依赖 Android，`core/network` 不理解角色，`core/data` 实现 HTTP 字段转换和 Room 对齐，`core/designsystem` 只管理外观。

`core/model`、`core/agent`、`core/tools` 与 `integrations/mcp` 仍是预留目录，不作为空 Gradle 模块加入构建。在线 Agent 继续在后端执行。
