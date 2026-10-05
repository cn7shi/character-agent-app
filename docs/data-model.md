# 两端存储与最小数据模型

状态：服务端已建立 `users`、`characters`、`character_revisions`、`conversations`、`request_receipts`、`messages`、`runs`、`run_events`、`tool_calls` 与 `notes`。Android Room 已有快照、待发送、会话创建请求及本地草稿表，当前 schema 2。长期记忆、资源管理与完整同步尚未实现。

## 数据归属

| 数据 | 服务端 | 手机本地 | 对齐原则 |
| --- | --- | --- | --- |
| 用户与身份 | 用户记录与认证关联 | 当前账号必要资料 | 服务端解析身份，不信任请求体传入的用户编号 |
| 角色 | 已发布资料、人设版本、所有者与允许的能力 | 展示资料；所有者编辑内容及已提交待确认保存 | 按标识和版本更新；私有人设仅通过所有者编辑接口返回 |
| 会话与消息 | 账号内已确认记录 | 最近记录、发送状态与待发送消息 | 稳定消息标识与会话序号，重试去重 |
| 在线任务与工具记录 | 执行状态、结果与事件 | 当前页面需要的状态快照 | 服务端负责执行；客户端不能把任务状态改成成功 |
| 笔记 | 工具实际保存的内容与来源 | 缓存笔记列表和详情 | 服务端按用户隔离，可按来源会话筛选 |
| 草稿 | 首版不保存 | 每个账号和会话的输入草稿 | 只在本机保存 |
| 记忆 | 后续保存有明确用户和角色范围的记忆 | 只缓存用户可查看的必要内容 | 默认角色内隔离，跨角色共享另行设计 |
| 主题与临时界面状态 | 首版不保存 | 简单设置存储或临时状态 | 不与聊天同步绑定 |
| 图片与音频 | 文件或对象存储及资源元信息 | 文件缓存和资源引用 | 数据库不保存界面组件或大段二进制资源 |

设备之间不会直接共享 Room 数据库。重装、换设备时只能恢复已同步到服务端的记录，本地草稿与未发送消息不承诺恢复。

## 通用约定

- 资源 ID 使用 UUID 字符串；接口字段使用 `snake_case`。
- 时间使用 UTC 的 ISO 8601 字符串，界面负责按用户时区显示。
- `version` 是服务端递增整数；角色 PUT 使用 expected_version 防止旧表单覆盖较新资料，相同版本与内容重试不重复发布。
- 列表使用不透明游标，默认 20 条，上限 100 条。
- 数量保存为整数；“96.4 万”等显示文本由界面格式化。
- 每个用户资源的读取和写入均检查用户归属，不能只凭 UUID 访问。
- 客户端生成的消息 ID 不自动成为服务端主键；通过 `client_message_id` 建立本地与远程记录关联。

## 第一批业务对象

| 对象 | 主要字段 | 约束与用途 |
| --- | --- | --- |
| `users` | `id`、`display_name`、`created_at` | 先提供明确的用户范围，真实账号认证后续接入 |
| `characters` | `id`、`name`、`summary`、`avatar_asset_id`、`background_asset_id`、`version`、`created_at`、`updated_at`、`owner_id`、`deleted_at` | owner_id 为空是内置角色，否则仅所有者可见；删除标记阻止新会话但保留历史关联 |
| `character_revisions` | `id`、`character_id`、`version`、`name`、`system_prompt`、`allowed_tool_ids` | 不可变的人设版本，包含当时名称；同一角色版本号唯一 |
| `conversations` | `id`、`user_id`、`character_id`、`character_revision_id`、`title`、`version`、`created_at`、`updated_at` | 首版每个会话固定一个角色和设定版本；同一用户可与同一角色开启多个会话 |
| `messages` | `id`、`conversation_id`、`user_id`、`run_id`、`client_message_id`、`sequence`、`role`、`blocks`、`status`、`created_at`、`updated_at` | 角色为 `user` 或 `assistant`；工具执行记录单独保存 |
| `runs` | `id`、`conversation_id`、`user_id`、`input_message_id`、`output_message_id`、`executor`、`model_name`、`is_simulated`、`status`、`last_event_sequence`、`error_code`、`error_message`、`created_at`、`updated_at` | 首版一个输入对应一个回复，执行归属固定为 `server`，显式区分模拟与真实模型 |
| `run_events` | `run_id`、`sequence`、`type`、`data`、`occurred_at` | 序号在任务内从 1 递增，唯一键为任务与序号 |
| `request_receipts` | `user_id`、`operation`、`idempotency_key`、`request_hash`、`resource_id`、`created_at` | 唯一键为用户、操作类型与请求键；开发阶段随对应资源保留 |

首版消息状态为 `pending`、`streaming`、`completed`、`failed`、`cancelled`。服务端已接收的用户消息直接为 `completed`，代表内容已持久化，不代表助手任务成功；客户端另用发送状态表达是否同步完成。

消息序号由服务端按会话分配，用户消息和回复占位各有固定序号。后续逐步更新回复内容，不更换消息 ID 或插入顺序。历史列表按序号降序分页，界面可按升序展示已获取记录。

外键与业务检查保证消息和任务的 `user_id`、`conversation_id` 相互一致。私有设定版本必须属于会话指定角色。不能使用一个全局会话或默认角色记录代替这些关联。

## 消息内容

首版至少支持 `text` 与 `narration` 两种内容块，普通模型无法稳定区分时先作为 `text` 保存。预留 `audio` 和 `image` 类型，相关上传、播放与生成不属于当前阶段。

```json
{
  "blocks": [
    {"type": "narration", "text": "他把整理好的清单递给你。"},
    {"type": "text", "text": "我整理好了，你可以先看一下。"}
  ]
}
```

客户端发送接口首版只接收纯文本，由后端转换为用户消息内容块。模型生成的台词不能作为工具成功的证据；保存笔记成功与否以工具执行记录为准。

## 工具与笔记（已实现）

| 对象 | 主要字段 | 边界 |
| --- | --- | --- |
| `tool_calls` | `id`、`user_id`、`conversation_id`、`run_id`、`call_id`、`tool_id`、`arguments`、`request_hash`、`status`、`result`、`error_code`、`error_message`、`created_at` | `(run_id, call_id)` 唯一；原始请求指纹用于区分重试与冲突；状态为 `completed` 或 `failed` |
| `notes` | `id`、`user_id`、`conversation_id`、`source_run_id`、`source_tool_call_id`、`title`、`content`、`created_at` | 调用记录与笔记用组合外键绑定用户、会话和任务，同一工具记录只能创建一份笔记 |

`notes.save` 仅接受去除首尾空白后 1–100 字符的标题和 1–16000 字符的正文。用户与会话由已认证身份和任务确定，拒绝通过工具参数指定身份。笔记按 `(created_at, id)` 降序分页，游标绑定用户和会话筛选条件。

执行前重新检查会话固定的人设版本权限。林舟版本 2 允许 `notes.save`，旧会话仍绑定版本 1；角色更新不会扩大已有会话的能力。

本地笔记、工具成功记录与事件在一个短事务中保存，失败时一起回滚；能够记录失败时，另存安全失败结果。取消与写入串行化，先取消就不再写笔记，先保存则保留。相同调用键和内容返回已保存结果（包括失败）；同键不同内容报冲突。服务重启保留已完成工具产物，不自动重放写操作。

当前工具没有跨网络中间状态。未来 MCP 调用需要独立设计运行中与结果不明状态，不能假定外部写入能随本地事务回滚。

## 后续预留对象

| 对象 | 主要字段 | 边界 |
| --- | --- | --- |
| `memories` | `id`、`user_id`、`character_id`、`conversation_id`、`source_message_id`、`content`、`created_at` | 首版限定角色范围，可进一步限定会话；原型世界观与用户个人记忆分开 |
| `assets` | `id`、`kind`、`storage_key`、`mime_type`、`duration_ms` | 保存文件引用；元信息按资源类型选用 |

记忆与资源仍是预留对象，本阶段不创建空表或推断复杂记忆算法。

## 客户端数据对象

Room 阶段 6A 实际保存 `snapshots`、`outbox` 与 `conversation_requests`。`snapshots` 以 `(scope, kind, id)` 作主键，保存领域快照的内部 JSON 与排序键；kind 区分角色、会话、消息、任务和笔记。领域对象、HTTP 响应与 Room 行通过 data 模块转换。当前不做本地全文检索，后续按需要拆分专用表，导出的 schema 用于迁移。

阶段 6C1 在 snapshots 新增内部 kind `character_write`，每个 scope 至多一份待确认角色保存，包含固定角色 ID、基准版本与完整内容。请求成功时与角色快照一同事务提交并清除意图；失败保留，重新打开后可重试。未点击保存的表单仍是 ViewModel 状态。列表只有完整分页成功才替换角色缓存，避免离线清空；删除不清除旧聊天。Room 结构仍为 schema 2。

每条记录都有连接范围 `scope`，当前由服务地址与访问凭证指纹产生，原始凭证不进 Room。切换连接后界面与观察任务一起切换，不能显示上一范围的数据。正式认证后使用服务端验证的稳定账号标识。

`outbox` 至少记录本地消息 ID、目标会话、请求键、请求内容和重试状态。网络恢复后沿用原请求键提交，不生成新键。首版草稿对应已有会话，不支持离线创建新会话后自动关联。

当前待发送项保留原请求内容、请求键、消息 ID、可展示错误与是否明确被拒绝；每次 I/O 前先保存确认状态，成功后与任务、消息快照同事务提交并移除待发送项。HTTP 超时只表示未收到结果。6B1 对幂等操作增加有限自动重试，耗尽后由用户手动重试；此前发生过受理不明的项目不能因之后的拒绝直接移除。

本地业务表不保存访问令牌、模型密钥或服务端私有提示词。开发连接使用 Android Keystore 加密保存，应用禁用备份。6B2A 新增 `drafts(scope, conversationId, revision, text)`，以范围和会话为主键，保存未经裁剪的原文；每次编辑更新 revision，空输入删除对应草稿。顺序写入并合并快速编辑；发送前排空先前编辑，在同一事务插入 outbox 并只删除匹配 revision 的草稿，保护后续新输入。

schema 1 → 2 使用保留旧表的增量迁移，测试从真实旧 schema 建库核验原请求键、正文及游标。草稿仅本机保存，恢复以已提交的本地数据为准，保存失败提供重试。断开隐藏数据但不清除隔离缓存；同一身份重连可恢复已保存草稿。

6B1 已把 Run.sequence（内部 JSON 的 last_event_sequence）作为持久的事件确认位置，与两条消息和任务状态放进同一 Room 事务，没有新增单独游标表；6B2A 为草稿升级到 schema 2，事件存储结构保持不变。同序号重放不更新，旧任务状态不覆盖新状态，终态保持不变。缺号或未知事件使用服务端完整快照核对，不能只跳过游标。工具产物通过笔记查询核对，当前不存客户端工具调用明细。

## 事务与恢复规则

创建执行时，在同一事务中完成请求去重登记、用户消息写入、回复占位和任务写入。相同请求键且内容一致时返回原资源，内容不一致返回冲突。重试去重检查先于“会话忙”检查。

对用户与非空 `client_message_id` 建唯一约束。同一消息用不同请求键重复提交时返回冲突，不再次生成回复。创建会话也使用请求键去重。

同一会话只允许一个 `queued`、`running` 或 `cancel_requested` 状态的任务。实现时必须用数据库约束或事务串行化保障，不能只依赖先查询再插入。

状态及消息更新与相应事件写入同一事务，事务成功后才能推送事件。订阅恢复按任务内事件序号去重；不依赖设备时间排序。

服务重启后把遗留非终态任务及未完成回复标记为失败，错误为 `RUN_INTERRUPTED`，保留已经保存的部分内容。首版不承诺执行自动恢复，事件恢复与重新执行是两个不同操作。

本阶段不提供消息编辑、删除和完整双向同步。以后增加这些能力时需要版本冲突与删除标记规则，不能只按本机时间覆盖。
