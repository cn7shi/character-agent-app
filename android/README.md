# Android 原生客户端

阶段 6A 建立 Kotlin/Compose 多模块工程、Room 缓存与待发送记录、HTTP 后端适配，以及角色、会话、聊天、笔记和连接设置页面。默认连接现有模拟后端，不需要真实 AI；首次加载与发送仍需要本机后端运行。

这是一份开发版客户端。当前没有正式登录、后台自动发送、SSE 订阅或原型级视觉还原。实际验证结果见 [阶段报告](../docs/stage-reports/06a-android-client.md)。

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
- 发送前保存消息标识与请求键。超时或断网时保留待发送项，手动重试同一请求；不会换新键重新生成。
- 明确拒绝受理的请求可以从待发送区移除。受理结果不明的请求必须先用原键重试核对，不能直接丢弃。
- 创建会话也保存请求键，响应丢失后再次点击同一角色会继续原创建操作。
- 每秒查询当前会话的活动任务快照；失败后延长间隔。返回列表或切到后台会停止相应观察，不取消服务端任务；明确点击“停止”才发取消请求。
- 任务与消息快照在同一 Room 事务保存，较旧任务序号不能覆盖新状态。重新进入会话时查询持久记录，不重新创建已受理任务。
- 深浅色切换只替换颜色，业务数据和任务不变。完整视觉主题、第二种聊天布局在阶段 7 实施。

草稿当前仅在 ViewModel 中按会话保存，旋转时保留，进程退出后不承诺恢复。尚无离线创建会话、消息编辑、笔记修改/删除、缓存清理与正式账号切换流程。

## 验证

在项目根目录运行：

```powershell
backend/.venv/Scripts/python.exe backend/scripts/check_android.py
```

脚本创建隔离的本机模拟后端，运行 Kotlin 网络与 Room 测试，并让真实 HTTP 适配发送两次聊天和一次笔记指令，核验落库、重开数据库和用户隔离。凭证只从临时文件读取，不写源码、命令参数或日志；结束后清理测试服务和数据。需要本机网络、JDK/SDK、Python 开发环境及 Gradle 依赖下载权限。

单独运行 `:core:network:test :core:data:testDebugUnitTest` 时，不配置隔离后端的真实 HTTP 用例会跳过。Room 测试采用 Robolectric（API 34），不能代替手机上的 UI、输入法、Keystore 与生命周期验收。

## 模块边界

`app` 负责依赖组装、导航、连接保存和 ViewModel；四个 `feature` 模块只读业务模型并发出操作回调。`core/domain` 不依赖 Android，`core/network` 不理解角色，`core/data` 实现 HTTP 字段转换和 Room 对齐，`core/designsystem` 只管理外观。

`core/model`、`core/agent`、`core/tools` 与 `integrations/mcp` 仍是预留目录，不作为空 Gradle 模块加入构建。在线 Agent 继续在后端执行。
