# 两端存储与最小数据模型

状态：已通过迁移建立 `users`、`characters`、`character_revisions`、`conversations`、`request_receipts`、`messages`、`runs`、`run_events`，阶段 5 新增 `tool_calls` 与 `notes`。Room、长期记忆与资源管理尚未实现；本文中尚未落地的对象仍为设计约定。

## 数据归属

| 数据 | 服务端 | 手机本地 | 对齐原则 |
| --- | --- | --- | --- |
| 用户与身份 | 用户记录与认证关联 | 当前账号必要资料 | 服务端解析身份，不信任请求体传入的用户编号 |
| 角色 | 已发布资料、人设版本与允许的能力 | 缓存可展示的角色资料 | 按标识和版本更新；私有提示词不下发 |
| 会话与消息 | 账号内已确认记录 | 最近记录、发送状态与待发送消息 | 稳定消息标识与会话序号，重试去重 |
| 在线任务与工具记录 | 执行状态、结果与事件 | 当前页面需要的状态快照 | 服务端负责执行；客户端不能把任务状态改成成功 |
| 笔记 | 工具实际保存的内容与来源 | 计划缓存笔记列表和详情 | 服务端按用户隔离，可按来源会话筛选 |
| 草稿 | 首版不保存 | 每个账号和会话的输入草稿 | 只在本机保存 |
| 记忆 | 后续保存有明确用户和角色范围的记忆 | 只缓存用户可查看的必要内容 | 默认角色内隔离，跨角色共享另行设计 |
| 主题与临时界面状态 | 首版不保存 | 简单设置存储或临时状态 | 不与聊天同步绑定 |
| 图片与音频 | 文件或对象存储及资源元信息 | 文件缓存和资源引用 | 数据库不保存界面组件或大段二进制资源 |

设备之间不会直接共享 Room 数据库。重装、换设备时只能恢复已同步到服务端的记录，本地草稿与未发送消息不承诺恢复。

## 通用约定

- 资源 ID 使用 UUID 字符串；接口字段使用 `snake_case`。
- 时间使用 UTC 的 ISO 8601 字符串，界面负责按用户时区显示。
- `version` 是服务端递增整数，用于识别较新的记录；可修改接口引入时再落实并发更新条件。
- 列表使用不透明游标，默认 20 条，上限 100 条。
- 数量保存为整数；“96.4 万”等显示文本由界面格式化。
- 每个用户资源的读取和写入均检查用户归属，不能只凭 UUID 访问。
- 客户端生成的消息 ID 不自动成为服务端主键；通过 `client_message_id` 建立本地与远程记录关联。

## 第一批业务对象

| 对象 | 主要字段 | 约束与用途 |
| --- | --- | --- |
| `users` | `id`、`display_name`、`created_at` | 先提供明确的用户范围，真实账号认证后续接入 |
| `characters` | `id`、`name`、`summary`、`avatar_asset_id`、`background_asset_id`、`version`、`created_at`、`updated_at` | 首批为服务端预置角色；公开展示数据与私有设定分开 |
| `character_revisions` | `id`、`character_id`、`version`、`system_prompt`、`allowed_tool_ids` | 私有、不可变的人设版本；同一角色版本号唯一 |
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

Room 计划保存 `cached_characters`、`cached_conversations`、`cached_messages`、`cached_runs`、`cached_notes`、`outbox` 与 `drafts`。每条私有记录带账号范围；切换账号时不能显示上一账号的数据或继续发送上一账号的请求。

`outbox` 至少记录本地消息 ID、目标会话、请求键、请求内容和重试状态。网络恢复后沿用原请求键提交，不生成新键。云端会话尚未创建时先保留草稿，首版不支持离线创建新会话后自动关联。

发送状态单独使用 `pending`、`sending`、`acknowledged`、`failed`。HTTP 超时只表示未收到结果，不能据此认定服务端未受理。永久校验错误交给用户处理，临时网络错误才进入有限重试。

本地业务表不保存访问令牌、模型密钥或服务端私有提示词。客户端所需凭证使用专门的安全存储方案，在认证阶段实现。

## 事务与恢复规则

创建执行时，在同一事务中完成请求去重登记、用户消息写入、回复占位和任务写入。相同请求键且内容一致时返回原资源，内容不一致返回冲突。重试去重检查先于“会话忙”检查。

对用户与非空 `client_message_id` 建唯一约束。同一消息用不同请求键重复提交时返回冲突，不再次生成回复。创建会话也使用请求键去重。

同一会话只允许一个 `queued`、`running` 或 `cancel_requested` 状态的任务。实现时必须用数据库约束或事务串行化保障，不能只依赖先查询再插入。

状态及消息更新与相应事件写入同一事务，事务成功后才能推送事件。订阅恢复按任务内事件序号去重；不依赖设备时间排序。

服务重启后把遗留非终态任务及未完成回复标记为失败，错误为 `RUN_INTERRUPTED`，保留已经保存的部分内容。首版不承诺执行自动恢复，事件恢复与重新执行是两个不同操作。

本阶段不提供消息编辑、删除和完整双向同步。以后增加这些能力时需要版本冲突与删除标记规则，不能只按本机时间覆盖。
