# 模型配置与凭证保护

当前支持 `mock` 和 `openai_compatible` 两种后端模型适配。兼容模式使用 Chat Completions 的文本 SSE 协议，尚未指定外部供应商，也没有用真实密钥调用付费模型。默认继续使用模拟回复。

不配置 AI 也可以推进开发：`mock-v2` 提供两条固定回复和规则驱动的真实笔记保存流程，见 [离线演示](offline-demo.md)。兼容文本模型仍未接入真实工具调用；切换供应商不会自动获得这项能力。

## 本机配置

从项目根目录复制空白示例（已有文件时不要覆盖）：

```powershell
Copy-Item backend/model.example.json backend/model.local.json
```

在本机编辑 `backend/model.local.json`。它被 Git 忽略，仓库中只保存不含凭证的示例。本机 JSON 是明文文件，Git 忽略不等于加密；正式部署应由部署环境或密钥管理服务注入凭证。

| 字段 | 用途 |
| --- | --- |
| `provider` | 默认 `mock`；确定服务后改为 `openai_compatible` |
| `base_url` | 服务的 API 基址，例如服务文档指定的 `/v1` 路径；不要附加 `/chat/completions` |
| `model_name` | 供应商的准确模型标识，由任务记录公开展示 |
| `api_key` | 仅保存在后端本机配置或环境变量中，不填入聊天、文档或 Android 源码 |
| `timeout_seconds` | 生成总时限，默认 30 秒，可设 1 至 120 秒；连接另有 10 秒限制 |
| `max_tokens` | 默认 1024，范围 1 至 8192；按供应商模型限制调整 |
| `token_limit_parameter` | 默认 `max_tokens`；要求新版参数的服务可改成 `max_completion_tokens` |

只有显式选择兼容模式并提供完整配置才会调用服务。配置错误会阻止启动，错误内容不显示配置值；不会悄悄回退到模拟模型。外部服务要求 HTTPS，仅本机回环测试允许 HTTP。拒绝 URL 中的账号密码、查询串或片段，凭证只放在认证请求头中。

环境变量优先于 JSON，对应 `CHARACTER_MODEL_PROVIDER`、`CHARACTER_MODEL_BASE_URL`、`CHARACTER_MODEL_NAME`、`CHARACTER_MODEL_API_KEY`、`CHARACTER_MODEL_TIMEOUT_SECONDS`、`CHARACTER_MODEL_MAX_TOKENS`、`CHARACTER_MODEL_TOKEN_LIMIT_PARAMETER`。`CHARACTER_MODEL_CONFIG_FILE` 可指定另一个本机 JSON 文件。当前不自动加载 `.env` 文件；可以用环境管理工具注入变量，不要在终端历史中明文粘贴密钥。

启动仍使用 `backend/run.ps1 -Dev`。当前身份机制仅供本机开发，配置真实模型不代表服务可公开部署。

## 兼容范围与失败语义

发送系统人设、本会话最近最多 10 个完整成功轮次及当前消息；超出 48000 字符时按完整轮次移除较早内容。此限制不是精确 token 计数，具体服务可能还需调整上下文预算。

支持纯文本增量、注释心跳、UTF-8 分片、多行 SSE 与可选用量帧。必须收到正常停止原因和 `[DONE]` 才算完整回复；提前 EOF、截断、内容过滤、工具调用或格式错误均标记失败并保留已保存文本。兼容模式尚不支持 Responses、图像、音频、工具调用或供应商专有参数。

不自动重试生成 POST，也不跟随重定向。连接断开或超时后，供应商可能已计算部分费用，客户端只能查询原任务状态；本机停止读取不承诺供应商一定停止计费。HTTP 客户端使用默认 TLS 证书校验，不隐式读取代理或 netrc 配置。

协议依据：[Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[流式事件](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)。不同供应商的兼容程度仍需用选定服务验证。

## Git 与日志保护

`.gitignore` 排除本机模型配置、`.env`、运行数据库、凭证目录、私钥、签名文件和日志。提交前检查暂存区实际内容；推送前检查将推送分支的可达历史，避免只删除最新文件却把旧密钥推上去。

当前工作区启用项目钩子。新的克隆需要在项目根目录执行一次：

```powershell
git config --local core.hooksPath .githooks
```

也可以主动检查：

```powershell
backend/.venv/Scripts/python.exe scripts/check_secrets.py --staged
backend/.venv/Scripts/python.exe scripts/check_secrets.py --history HEAD
```

检查只输出文件名与问题类别，不输出匹配值。扫描覆盖敏感文件名、常见令牌格式、私钥内容和明文凭证赋值；不是所有格式的密钥都能自动识别，仍需在提交前检查改动。Git 钩子是本地防误操作措施，不是服务端强制策略。

模型配置中的密钥使用 `SecretStr` 避免意外打印；服务运行期间对日志消息和异常中的当前模型密钥脱敏。供应商原始报错正文不写入任务、不转发客户端。后端只保存模型名称、任务状态与固定的可展示错误，Android 不接收供应商凭证。

不要关闭钩子绕过检查。若真实密钥已进入远程历史，应先在供应商处撤销或轮换，再处理历史；补 `.gitignore` 不能消除已经泄露的凭证。
