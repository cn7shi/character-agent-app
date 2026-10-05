# 角色应用服务端

已实现持久化聊天、兼容文本适配、凭证保护、最小 Agent 和真实保存的笔记工具。阶段 6C1 新增私有角色管理，6C2 新增按身份隔离的昵称、简介读取、保存与重置，包含版本冲突与重复请求保护。重置仅清空资料，保留账号和其他业务数据。默认模拟聊天不需要 AI 密钥。外部供应商尚未选定，真实模型工具适配、MCP 和正式账号认证尚未完成。

## 启动

本地工作区已配置 `backend/.venv`，从 GitHub 克隆后请先按“新环境安装”创建环境。在 `backend` 目录打开 PowerShell：

```powershell
.\run.ps1 -Dev
```

服务只监听本机 `127.0.0.1:8000`，可通过 `-Port 8001` 修改端口。查看 [本机接口说明](http://127.0.0.1:8000/docs) 或 [健康检查](http://127.0.0.1:8000/health)，停止时按 Ctrl+C。

`-Dev` 显式开启开发身份和示例数据。首次启动会在 `.runtime/app/development-users.json` 生成两个随机访问凭证，供本机联调；源码不含固定令牌，日志不输出令牌。接口需要 `Authorization: Bearer <token>`。默认不加 `-Dev` 时只开放健康检查，业务请求拒绝开发身份，接口文档关闭。

启动脚本关闭代理头推断，并固定回环监听；不要把当前开发服务作为正式部署使用。应用还会检查开发模式的请求来源。`/docs` 和 `/openapi.json` 是仅开发模式启用的本机元数据入口，不需要业务访问令牌。

## 新环境安装

已验证环境为 Windows、Python 3.14.6，依赖版本固定在 `requirements.txt` 与 `requirements-dev.txt`。新环境需要 Python 3.14 与 uv，然后在本目录执行：

```powershell
uv venv .venv --python 3.14
uv pip install --python .venv\Scripts\python.exe -r requirements-dev.txt
```

默认数据位置为 `.runtime/app/character.sqlite3`，不使用原型数据库。需要单独数据目录时设置 `CHARACTER_DATA_DIR`，开发模式会同时把游标密钥与开发凭证保存到该目录。运行数据与凭证均不应提交到版本库。

真实模型配置见 [模型配置与凭证保护](../docs/model-configuration.md)。复制空白 `model.example.json` 到被忽略的 `model.local.json` 后，在本机填写；环境变量可覆盖配置。新克隆还需在项目根目录执行 `git config --local core.hooksPath .githooks` 启用提交和推送检查。

## 当前接口

| 操作 | 行为 |
| --- | --- |
| `GET /health` | 健康检查 |
| `GET /v1/me/profile` | 当前用户昵称、简介、版本，未设置时返回空资料 |
| `PUT /v1/me/profile` | 首次保存或编辑资料，检查版本与重复请求 |
| `POST /v1/me/profile/reset` | 清空昵称和简介，保留版本与其他业务数据 |
| `GET /v1/characters` | 角色分页列表 |
| `GET /v1/characters/{character_id}` | 角色公开资料，不返回私有人设 |
| `GET /v1/characters/{character_id}/definition` | 仅所有者可读的当前人设与笔记能力 |
| `PUT /v1/characters/{character_id}` | 创建或编辑自己的角色，expected_version 检查并发与重复保存 |
| `DELETE /v1/characters/{character_id}` | 幂等移除自己的角色，保留旧会话与笔记 |
| `POST /v1/conversations` | 创建独立会话，支持请求去重 |
| `GET /v1/conversations` | 当前用户的会话分页列表 |
| `GET /v1/conversations/{conversation_id}/messages` | 当前会话消息，按序号倒序分页 |
| `POST /v1/conversations/{conversation_id}/runs` | 保存用户消息并受理回复任务，首次 202、重试 200 |
| `GET /v1/runs/{run_id}` | 查询任务与回复快照 |
| `GET /v1/runs/{run_id}/events` | SSE 事件，支持 Last-Event-ID 恢复 |
| `POST /v1/runs/{run_id}/cancel` | 幂等请求取消，保留已保存的部分回复 |
| `GET /v1/runs/{run_id}/tool-calls` | 当前任务的工具调用记录 |
| `GET /v1/notes` | 当前用户的笔记分页列表，可按会话筛选 |
| `GET /v1/notes/{note_id}` | 当前用户的笔记详情 |

创建会话需要 UUID 格式的 `Idempotency-Key`，请求体包含 `character_id` 和可选 `title`。省略或传入 `null` 时使用角色名称；文本先去除首尾空白，再检查 1 至 100 个字符。首次返回 201，相同请求重试返回 200，同键不同内容返回 409。

聊天请求包含 `client_message_id` 与 `text`，也需要 `Idempotency-Key`；重试时两个标识和内容都保持不变。返回 `run_id` 后可查询状态或订阅事件，关闭订阅不会取消生成。任务字段 `is_simulated` 区分模拟与供应商适配，默认模拟模式不需要密钥。

当前一个数据目录只允许一个开发服务进程。后台最多同时运行 8 个任务，每个会话只能有一个活动任务。默认生成时限为 30 秒，可在模型配置中调整；回复上限 16000 字符。重启将未完成任务标记失败，不自动重放。启动脚本保持单进程，不要增加 `--workers`。

默认 `mock-v2` 提供两条固定回复。在林舟的新会话发送“记笔记：周末安排；买咖啡；看电影”，会按固定规则整理并真正保存笔记；已有会话仍使用原人设权限。最多 3 次工具轮次加 1 次回复轮次，只有真实保存结果才能触发成功回复。取消不撤销已提交的笔记，重试同一次调用不重复保存。使用方法见 [无 AI 联调演示](../docs/offline-demo.md)。

完整规则见 [接口约定](../contracts/api-v1.md)。[生成的 OpenAPI](../contracts/openapi-v1.json) 描述当前已实现的 19 项操作。

## 验证

```powershell
.\run-tests.ps1
.\.venv\Scripts\python.exe scripts\smoke_server.py
.\.venv\Scripts\python.exe scripts\export_openapi.py
.\.venv\Scripts\python.exe scripts\demo_chat.py
```

第一条运行 70 项测试，覆盖聊天、供应商协议、凭证保护、工具权限与笔记、私有角色管理，以及个人资料隔离、重试与版本冲突、重置保留、事务失败、服务重启和旧库迁移。第二条强制使用模拟配置启动本机服务，验证断流重连、取消、中断恢复、笔记持久化与重试；结束后清理测试进程与数据。第三条导出 OpenAPI。第四条在独立临时数据库运行两条固定回复和一次笔记保存，输出对话与笔记。导出与演示不读取本机模型配置，全部自动测试都不调用外部付费模型。

Windows 的异步运行时需要本机回环连接；在限制回环的沙箱中执行测试会阻塞，应在允许本机网络的开发终端运行。当前测试依赖的 TestClient 对 httpx 兼容接口有弃用提示，不影响已通过的测试；后续统一升级测试客户端时处理。

## 模块职责

| 目录 | 职责 |
| --- | --- |
| `src/character_app/api` | 身份解析、请求校验、响应模型和路由 |
| `src/character_app/domain` | 实体、错误和仓库接口 |
| `src/character_app/application` | 业务操作、分页游标、任务调度、工具注册与事件订阅 |
| `src/character_app/adapters/storage` | SQLite 仓库、短连接、事务和迁移 |
| `src/character_app/adapters/models` | 模拟与 OpenAI 兼容文本适配、客户端生命周期及日志保护 |
| `src/character_app/adapters/tools` | 内置笔记工具；扩展其他工具实现的位置 |
| `src/character_app/adapters/mcp` | 远程 MCP 接入预留 |
| `migrations` | 带校验记录的数据库迁移 |
| `tests` | 用独立临时数据库验证业务行为 |

`main.py` 组装依赖并统一错误格式。接口不直接操作 SQL，领域模块不依赖 FastAPI。关键选择的原因见 [设计决策](../docs/decisions.md)，最新功能结果见 [6C2 报告](../docs/stage-reports/06c2-personal-profile.md)，当前停点与接手流程见 [交接说明](../docs/handoff.md)。
