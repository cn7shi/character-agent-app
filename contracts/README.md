# 客户端与服务端接口约定

这里保存与 Android 界面和模型供应商无关的业务接口约定。阶段 5 已实现角色、会话、持久化任务、流式事件、工具记录与笔记查询。

- [API 与事件草案](api-v1.md)：请求、响应、错误、流式事件与恢复规则。
- [生成的 OpenAPI](openapi-v1.json)：包含当前实际实现的 13 项操作。
- [创建执行请求示例](examples/create-run.request.json)。
- [执行受理响应示例](examples/create-run.response.json)。
- [消息更新事件示例](examples/message-updated.event.json)。

创建执行与消息事件示例是用于解释协议的人工构造样例，不是运行截图或真实模型输出。已实现接口由 `backend/scripts/export_openapi.py` 从 FastAPI 路由导出；修改接口后更新并核对生成文件。
