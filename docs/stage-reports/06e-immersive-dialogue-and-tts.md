# 阶段交付报告：06e 沉浸式对话流、Neural TTS 与微信式排版优化

## 1. 交付目标

本阶段在 06d 广场与导航复刻的基础上，完成角色对话主链路的体验优化与真实模型能力闭环：
1. 实现类微信对话交互体验，输入栏固定吸底，消息发送后自动滚动触底。
2. 修复角色动作描写解析漏洞，杜绝段落缺失与大面积留白。
3. 接入微软 Edge-TTS 神经语音合成服务，完成端到端角色音频播放与本地缓存。
4. 验证 Groq Qwen 27B 真实大模型在线对话推理链路。

## 2. 完成内容与技术实现

### 2.1 微信式对话滚动与输入吸底 (`HomeScreen.kt`)

- **布局结构**：将原静态单卡片替换为 `LazyColumn` 消息流与底部固定输入栏 (`HomeBottomBar`)。
- **自动吸底机制**：使用 `rememberLazyListState` 监听消息列表长度，每当新增用户输入或角色回复，调用 `animateScrollToItem` 平滑滚动至最新气泡。
- **气泡分离**：用户消息右浮动 (`HomeUserBubble`)，角色回复左浮动 (`HomeAssistantBubble`)。

### 2.2 剧本动作解析器重构 (`ReplyParser.kt` & `HomeChatBubbles.kt`)

- **缺陷根因**：原非贪婪正则模式 `[\(（\*](.*?)[\)）\*]` 在遇到带有嵌套英文括号的内容（如 `(resist)`、`(run)`）时提前闭合，随后的 `replace` 逻辑将整段叙事动作清除，造成空行累加与大面积空白。
- **重构实现**：
  - 改用段落边界切分 (`split("\n\n")`) 配合括号匹配算法，准确提取首段动作描述作为引导语。
  - 保留所有正文中的括号叙事动作，不执行全局删除。
- **排版分层**：
  - 动作描写：`Color(0xB3FFFFFF)`，`FontStyle.Italic`，半透斜体展示。
  - 台词对白：`Color.White`，`FontWeight.Medium`，白字加粗展示。
  - 段间距：统一设置为 `8.dp`，视觉排版紧凑。

### 2.3 神经语音合成服务 (`tts_service.py` & `VoicePlayer.kt`)

- **服务端实现 (`backend/src/character_app/application/tts_service.py`)**：
  - 集成 `edge_tts` 异步引擎，对外提供 `POST /v1/tts/synthesize` 与 `GET /v1/tts/synthesize` 接口。
  - **动作剥离算法 (`parse_dialogue_and_emotion`)**：提取并剥离括号内的舞台动作，只将台词送入合成引擎，防止朗读动作指示。
  - **角色音色分发 (`resolve_character_voice_config`)**：
    - 霍司爵 / 严烈：`zh-CN-YunjianNeural`（语速 -4%，音调 -6Hz）。
    - 萧寒：`zh-CN-YunyangNeural`（语速 -5%，音调 +0Hz）。
    - 希尔达：`zh-CN-XiaoxiaoNeural`（语速 +2%，音调 +0Hz）。
    - 江野：`zh-CN-YunxiNeural`（语速 +8%，音调 +4Hz）。
- **客户端实现 (`android/feature/characters/src/main/kotlin/com/character/app/characters/VoicePlayer.kt`)**：
  - 采用 MD5 文本哈希作为文件名，缓存在 `context.cacheDir/tts_cache`。
  - 优先读取本地 MP3 文件，未命中时通过 HTTP 请求后端并在下载完成后调用 `MediaPlayer` 播放。
  - 状态同步：通过 Compose `State` 暴露 `isPlaying`、`isLoading` 及播放倒计时，驱动气泡上的 4 根动态音波条。
  - 离线容灾：后端网络不可用时，自动降级至系统 `TextToSpeech`。

### 2.4 真实模型接入与架构解耦

- **模型配置 (`backend/model.local.json`)**：
  - 提供方：`openai_compatible`
  - 端点：`https://api.groq.com/openai/v1`
  - 模型：`qwen/qwen3.8-27b`
  - 凭据通过安全配置加载，日志执行敏感信息脱敏。
- **代码重构**：
  - `HomeScreen.kt` 拆分为 `HomeSynopsisCard`、`HomeAssistantBubble`、`HomeUserBubble` 与 `HomeBottomBar`。
  - 数据层解耦出 `MessageOutbox`（离线重试队列）与 `ConversationSyncer`（SSE 状态同步）。

## 3. 验证数据

| 验证项 | 验证方式 | 执行结果 |
| :--- | :--- | :--- |
| 回复解析测试 | `:feature:characters:testDebugUnitTest` | 100% 通过（含嵌套括号、纯动作、混合对白用例） |
| 音频合成接口 | `POST /v1/tts/synthesize` 本地请求 | 返回 HTTP 200，生成 31,104 字节 MP3，时长 6.5 秒 |
| 端到端真机运行 | Android 15 (API 35) 模拟器 | 发送消息正常流式返回，气泡分段渲染无留白，语音点击可播 |
| 音频缓存验证 | `run-as com.character.app ls -la cache/tts_cache` | 成功生成 46,080 字节与 56,304 字节的缓存文件 |

## 4. 当前系统状态总结

截至当前阶段，应用已实现：
- 完整的五键底部导航（首页、广场、创作、实验玩法、消息）。
- 真实的 Groq Qwen 27B 角色模型流式对话。
- 真实的 Edge-TTS 多角色拟真配音。
- 完整的本地 Room 离线缓存与 Outbox 队列重试机制。
