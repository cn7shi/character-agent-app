# 业务 API 与流式事件草案

状态：阶段 2 与 3 的 10 项操作已经实现并通过验证，工具相关事件仍为草案。已实现响应以 [生成的 OpenAPI](openapi-v1.json) 为准。接口独立于具体界面、Room 表结构和模型供应商；存储约束见 [数据模型](../docs/data-model.md)。

## 通用约定

- 业务路径前缀为 `/v1`；请求和响应为 UTF-8 JSON，事件流使用 SSE。
- 除健康检查外，所有业务接口需要认证。服务端从已验证身份获得用户范围，不接受请求体指定 `user_id`。开发模式下本机 `/docs` 与 `/openapi.json` 仅提供接口元信息。
- 身份解析器与业务分开。阶段 2 本地开发可显式启用两个隔离开发用户，只绑定回环地址；默认模式拒绝开发身份。正式登录认证尚未接入。
- UUID 用字符串表示，字段使用 `snake_case`，时间为 UTC ISO 8601。
- 查询不存在或不属于当前用户的私有资源，统一返回 `404 RESOURCE_NOT_FOUND`。
- 创建会话和创建执行需要 `Idempotency-Key` 请求头，值为客户端生成的 UUID。取消接口本身幂等。
- 分页请求使用 `cursor` 和 `limit`，响应包含 `items` 与 `next_cursor`；最后一页为 `null`。默认 20 条，上限 100 条。
- 校验错误使用 `422 VALIDATION_ERROR`。HTTP 错误不包装成成功的 200 响应。

## 接口范围

| 方法与路径 | 用途 | 正常返回 | 实现阶段 |
| --- | --- | --- | --- |
| `GET /health` | 存活检查，不包含内部配置 | 200，`{"status":"ok"}` | 2 |
| `GET /v1/characters` | 当前用户可见的角色列表 | 200，分页角色资料 | 2 |
| `GET /v1/characters/{character_id}` | 获取可见角色详情 | 200，角色资料 | 2 |
| `POST /v1/conversations` | 为角色创建独立会话 | 首次 201，重试 200，会话对象 | 2 |
| `GET /v1/conversations` | 当前用户的会话列表 | 200，分页会话对象 | 2 |
| `GET /v1/conversations/{conversation_id}/messages` | 已保存的历史消息 | 200，分页消息对象 | 3，已支持真实保存的消息 |
| `POST /v1/conversations/{conversation_id}/runs` | 原子受理用户消息并创建回复任务 | 首次 202，重试 200，执行对象 | 3 |
| `GET /v1/runs/{run_id}` | 读取执行状态及当前回复快照 | 200，执行对象 | 3 |
| `GET /v1/runs/{run_id}/events` | 订阅或恢复事件流 | 200，`text/event-stream` | 3 |
| `POST /v1/runs/{run_id}/cancel` | 请求停止后续生成或执行 | 200，当前执行对象 | 3 |

角色列表按固定 `(created_at, id)` 升序分页，会话列表按 `(updated_at, id)` 降序分页，消息按 `sequence` 降序分页。游标由服务端产生并校验；会话列表在更新期间允许出现重复项，客户端按 ID 合并，首版不承诺跨请求快照一致性。

角色公开响应包含 `id`、`name`、`summary`、`avatar_asset_id`、`background_asset_id`、`version`，可空资源字段返回 `null`，不返回私有提示词与工具凭证。资源下载接口后续加入，首版资源字段可为空。

## 创建会话

请求体包含 `character_id` 和可选 `title`。`title` 去除首尾空白后的长度为 1 至 100 个 Unicode 字符，省略或为 `null` 时由角色名称生成。首次创建固定使用角色当前已发布的人设版本；重复请求返回原会话，不切换到新版本。

```json
{
  "character_id": "11111111-1111-4111-8111-111111111111",
  "title": "第一次聊天"
}
```

会话响应包含 `id`、`character_id`、`title`、`version`、`created_at`、`updated_at`。角色不存在或不可见返回 `404 RESOURCE_NOT_FOUND`。请求键对当前用户的 `create_conversation` 操作唯一，同键不同规范化请求体返回 `409 IDEMPOTENCY_CONFLICT`。

## 创建执行

请求示例见 [请求样例](examples/create-run.request.json)。`text` 去除首尾空白后必须非空，长度不超过 8000 个 Unicode 字符；首版不接收附件、任意工具列表或供应商原始参数。

`client_message_id` 是本地消息关联标识，`Idempotency-Key` 是该次创建操作的重试标识，两者在重试时保持不变。请求键对当前用户的 `create_run` 操作唯一；服务端对会话路径与经过相同校验规范化后的请求体计算指纹，同键更换内容或会话返回 `409 IDEMPOTENCY_CONFLICT`。

同一用户用另一个请求键重复提交相同 `client_message_id` 时返回 `409 CLIENT_MESSAGE_CONFLICT`。如果是新请求且会话有未结束任务，返回 `409 CONVERSATION_BUSY`，不新增消息或任务。

受理响应见 [响应样例](examples/create-run.response.json)。响应中的 `input_message` 和 `output_message` 分别是已保存用户消息和回复快照。首次响应回复为 `pending`；重复请求返回同一任务的当前状态，可能已经完成。

消息对象包含 `id`、`conversation_id`、`run_id`、`client_message_id`、`sequence`、`role`、`blocks`、`status`、`created_at`、`updated_at`。助手消息的 `client_message_id` 为 `null`。每个执行首版只对应一个助手消息；工具结果用执行记录和事件表达。

从客户端断开事件流不取消执行。切回页面时获取同一 `run_id` 的状态或恢复事件，不再创建新的执行。

## 状态与取消

正常流转为 `queued → running → completed`。失败进入 `failed`，取消可以从 `queued` 直接到 `cancelled`，或由 `running → cancel_requested → cancelled`。

`completed`、`failed`、`cancelled` 是不可更改的终态。取消与完成并发时，以先提交的终态为准；进入终态后再次取消返回当前状态。`cancel_requested` 表示已记录停止意图，还不能向用户显示已停止。

执行对象包含 `id`、`conversation_id`、`executor`、`model_name`、`is_simulated`、`status`、`last_event_sequence`、`input_message`、`output_message`、`error`、`created_at`、`updated_at`。非失败状态 `error` 为 `null`，失败时至少包含 `code` 和可展示的 `message`。阶段 3 使用 `mock-v1`，`is_simulated` 为 `true`，文本也明确标记模拟回复。

模型生成超时使用 `MODEL_TIMEOUT`，模型异常或超过输出限制使用 `MODEL_ERROR`，开发服务重启中断使用 `RUN_INTERRUPTED`。已保存的部分回复保留并标记失败或取消；助手消息仅在生成正常结束时标记 `completed`。

当前模型读取时限为 30 秒，输出上限为 16000 字符；每个服务最多同时执行 8 个任务，同会话仅一个活动任务，其余会话可以排队。开发版无跨进程队列，数据目录通过进程锁限制一个执行服务。待实现正式认证、配额与部署限流后再开放对外访问。

## 流式事件

SSE 的 `id` 为当前任务内递增的整数序号，`event` 为事件类型，`data` 为单行 JSON。事件信封固定包含 `run_id`、`sequence`、`type`、`occurred_at` 和 `data`。`id` 与 `sequence` 一致，`event` 与 `type` 一致。

| 事件类型 | data 内容 | 阶段 |
| --- | --- | --- |
| `run.accepted` | `status: queued` | 3，创建时作为序号 1 保存 |
| `run.started` | `status: running` | 3 |
| `message.updated` | `message`，完整消息快照 | 3 |
| `run.cancel_requested` | `status: cancel_requested` | 3 |
| `run.completed` | `status: completed` 与最终 `output_message` | 3 |
| `run.failed` | `status: failed`、`error` 与当前 `output_message` | 3 |
| `run.cancelled` | `status: cancelled` 与当前 `output_message` | 3 |
| `tool.started`、`tool.completed`、`tool.failed` | 工具调用摘要，结构在工具阶段补充 | 5，当前不发送 |

消息更新首版使用完整快照，内容可以逐步增长。客户端按消息 ID 替换内容，不把重复快照追加成重复台词。事件样例见 [消息事件](examples/message-updated.event.json)。传输示意如下，实际 `data` 放入样例文件中的完整 JSON：

```text
id: 3
event: message.updated
data: <单行 JSON 事件信封>

```

省略 `Last-Event-ID` 时从第 1 个事件开始，携带时只发送大于该序号的事件。序号必须是 0 或正整数，超过任务当前最大序号返回 `400 INVALID_EVENT_CURSOR`。客户端忽略已应用序号，并在同一 Room 事务中保存消息快照和最后应用序号。

开发阶段事件随任务保留，不做过期裁剪。回放历史与切换到实时订阅必须避免遗漏事件；实现可重复读取持久事件记录，不能只订阅瞬时内存通知。收到终态事件后关闭流；已终态任务回放结束后也关闭。

心跳使用 SSE 注释，不占用业务序号。意外断流不代表成功；客户端查询任务状态或恢复订阅。流已开始后的业务失败使用 `run.failed`，不尝试再发送另一份 HTTP JSON 错误体。

## 错误格式

```json
{
  "error": {
    "code": "CONVERSATION_BUSY",
    "message": "当前会话还有一个任务未结束。",
    "request_id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
  }
}
```

未认证为 `401 AUTH_REQUIRED`；资源不可访问为 `404 RESOURCE_NOT_FOUND`；请求冲突为 409；字段校验为 422；服务端故障为 `500 INTERNAL_ERROR`。`request_id` 由服务端生成用于定位单次请求，与业务去重键不同。错误响应不包含服务凭证、SQL 或内部堆栈。

## 必须在实现阶段验证的行为

- 两个用户不能读取或操作对方的会话、消息、任务与事件流。
- 相同创建请求并发重试只生成一套资源；同键不同内容返回冲突。
- 同一角色可以拥有多个独立会话，不共享会话消息。
- 事件重连不重复拼接文本，不重复调用模型或工具。
- 取消、失败和断流不能显示成成功；重启不自动重放写操作。

阶段 3 累计通过 27 项行为测试，验证消息与任务原子保存、并发重试去重、访问隔离、分页、取消、超时、迁移升级、事件多批回放、单执行器锁及中断恢复。真实 HTTP/SSE 检查已验证断流重连、取消和进程重启；真实模型与 Android Room 同步尚待后续阶段验证。
