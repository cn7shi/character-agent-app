# 业务核心

状态：纯 Kotlin 模块，提供业务对象、仓库、BackendAdapter 与 RunEvent，不依赖 Android。ReliableBackend 实现有限重试，RunMonitor 实现游标恢复/去重/快照回退；具体 HTTP 和 Room 不进入本模块。

负责角色、会话、消息、记忆、任务等业务实体，业务操作，以及数据仓库、模型、Agent 与工具的接口约定。

保持为独立于界面的 Kotlin 模块，不依赖 Compose、Android 页面、数据库或供应商 SDK。需要的具体能力通过接口传入。
