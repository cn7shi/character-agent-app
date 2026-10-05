# Android 原生客户端

已建立 Kotlin/Compose 多模块工程、Room 缓存与待发送记录、HTTP/SSE 后端适配，以及角色、会话、聊天、笔记和连接设置页面。默认连接现有模拟后端，不需要真实 AI；首次加载与发送仍需要本机后端运行。

这是一份开发版客户端。当前没有正式登录、后台自动发送或原型级视觉还原。网络恢复、本地草稿与数据库升级已完成本机测试，并已完成 13 个 Android 15 设备/主机检查点，含前台强杀、部分回复取消和历史 APK 覆盖升级；完整设备验收仍有未覆盖项。实际验证见 [阶段报告](../docs/stage-reports/06b2c-recovery-and-upgrade.md)，策略详见 [网络可靠性](../docs/network-reliability.md)。

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

角色页点“创建角色”，填写名称（1–100 字）、简介（0–500 字）和人设（1–8000 字），可开启“允许保存笔记”。保存后卡片显示“我的角色 · 仅自己可见”，可开聊、编辑、删除。删除有确认并保留旧对话；旧对话使用创建时的人设与能力，改动用于新对话。内置角色不可修改。固定模拟回复不会因为填写了人设就变成真实人设化 AI。

角色保存失败后显示“继续确认角色保存”，原 ID、版本和内容已经留在 Room，重开后可重试。待确认内容不能直接修改；已有角色可明确放弃改动、重新读取服务端版本。点击保存之前的表单不提供跨进程恢复。头像与背景上传、公开分享、个人资料管理尚未完成。

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

脚本创建隔离的本机模拟后端，运行 Kotlin 策略、网络、Room、迁移与 ViewModel 共 65 项测试。内存模拟和两种 HTTP 模式运行同一聊天业务契约，真实 SSE 直接更新 Room，并发送聊天和笔记指令，核验落库、重开数据库和用户隔离；另覆盖草稿、发送交接、旧库升级、模式切换、取消、退出时序，以及角色待确认保存恢复、缓存删除、冲突重读和两种 HTTP 模式的角色管理。凭证只从临时文件读取，不写源码、命令参数或日志；结束后清理测试服务和数据。需要本机网络、JDK/SDK、Python 开发环境及 Gradle 依赖下载权限。

单独运行 `:core:domain:test :core:network:test :core:data:testDebugUnitTest :app:testDebugUnitTest` 时，不配置隔离后端的 4 项真实 HTTP 用例会跳过。Room 与 ViewModel 测试采用 Robolectric（API 34），不能代替设备检查。草稿复查见 [草稿报告](../docs/stage-reports/06b2a-persistent-drafts.md)，设备进度见 [验收清单](../docs/device-acceptance.md)。

### 设备自动检查

启动专用模拟器并从 `adb devices` 确认序列号，安装后端开发依赖后，在仓库根目录执行（替换成实际设备）：

```powershell
backend/.venv/Scripts/python.exe backend/scripts/check_device.py --serial emulator-5580
```

脚本自动编译 `deviceCheck` 和对应测试包，通过 `-PdeviceChecks=true` 启用设备测试构建。普通 debug 构建及原有单元测试入口保持可用。需要 JDK 21、SDK 36、可用的 adb 设备和后端虚拟环境；SDK 不在默认位置时传入 `--sdk <SDK目录>`。`--skip-build` 仅用于已经重新编译过的测试包，不会自动确认源码是否改变。

专用包名为 `com.character.app.devicecheck`，每轮重置它自己的测试数据；遇到没有脚本标记的同名包会停止。正常开发包 `com.character.app` 不受影响。检查点按顺序共享临时数据，必须由此脚本运行，不能随机排序单独批量运行；其中前台强杀检查需要主机同步配合。

每轮使用独立模拟后端，自动验证连接和聊天、草稿恢复/清空/发送交接、笔记、主题、实时与兼容模式、开发身份隔离，以及停服后的缓存和原请求重试。凭证只经标准输入写入应用私有临时配置，结束后清除；设备日志先脱敏，截图不取凭证页。原始结果保存在被忽略的 `backend/.runtime/device-evidence-<UTC时间>/`，包含 `results.json`、逐项输出和六张截图。服务与转发会清理；专用应用保留安装以便下一轮复用。

扩展检查另验证前台运行中强杀、部分回复取消后继续聊天，以及真实 schema 1 APK 覆盖升级。先构建一次固定历史版本，再执行：

```powershell
backend/.venv/Scripts/python.exe backend/scripts/build_device_baseline.py
backend/.venv/Scripts/python.exe backend/scripts/check_device.py --serial emulator-5580 --extended
```

历史源码从已提交 Git 对象导出，唯一源码覆盖是专用包名；本机 SDK 配置不入库。两版使用同一本机调试签名，安装包与历史源码保存在忽略目录 `backend/.runtime/device-baseline/`，执行前核对 APK 哈希。旧版准备数据之前会重置专用包；旧版升级到新版的过程中只执行覆盖安装，不清库。扩展运行共 13 个设备/主机检查点、12 张截图，其中 12 项 JUnit 正常完成，1 项刻意终止前台测试进程并由主机验收。缺少历史包时脚本会提示先构建。

本机 `CharacterAgent_API35` AVD 使用已有 API 35 镜像，数据在忽略目录中，不提交 Git。自动化输入和生命周期操作不代表完整输入法/实体手机验收；剩余范围以 [设备清单](../docs/device-acceptance.md) 为准。

角色管理可单独验收，复用同样的隔离与清理机制，不需要先构建历史 APK：

```powershell
backend/.venv/Scripts/python.exe backend/scripts/check_device.py --serial emulator-5580 --characters
```

执行两组实际页面流程：创建、开聊和保存笔记；强制重开后编辑、取消删除、确认删除、继续旧对话。另由主机核对跨身份不可见与笔记真实保存，共四张截图。`--characters` 与 `--extended` 互斥。结果见 [角色管理报告](../docs/stage-reports/06c1-character-management.md)。

## 模块边界

`app` 负责依赖组装、导航、连接保存和 ViewModel；四个 `feature` 模块只读业务模型并发出操作回调。`core/domain` 不依赖 Android，`core/network` 不理解角色，`core/data` 实现 HTTP 字段转换和 Room 对齐，`core/designsystem` 只管理外观。

`core/model`、`core/agent`、`core/tools` 与 `integrations/mcp` 仍是预留目录，不作为空 Gradle 模块加入构建。在线 Agent 继续在后端执行。
