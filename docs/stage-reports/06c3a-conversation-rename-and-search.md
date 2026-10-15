# 阶段报告：06c3a 会话重命名与搜索功能落地

本文档记录阶段 06c3a 的技术方案与交付成果。本阶段完成了会话重命名（Conversation Rename）与即时搜索（Instant Search）的端到端贯通。

---

## 一、接口与协议变更

### 1. HTTP 协议定义

- **路径**：`PATCH /v1/conversations/{conversation_id}`
- **请求体**：
  ```json
  {
    "title": "霍司爵 - 密谈01",
    "expected_version": 1
  }
  ```
- **响应体**（`200 OK`）：
  ```json
  {
    "id": "ced7ee95-ddff-457d-b2ef-1429207b3a37",
    "character_id": "2310143e-1e29-46db-9525-0f45bd2a16da",
    "title": "霍司爵 - 密谈01",
    "version": 2,
    "created_at": "2026-10-15T15:20:00Z",
    "updated_at": "2026-10-15T15:38:00Z"
  }
  ```
- **并发控制**：服务端采用乐观锁。若 `expected_version` 与数据库当前版本不一致，返回 `409 CONVERSATION_VERSION_CONFLICT`；若同版本同标题重试，触发幂等返回。

---

## 二、端到端架构实现

### 1. 服务端（Python / FastAPI / SQLite）

- `backend/src/character_app/api/schemas.py`：定义 `RenameConversationRequest`，字段长度限定 1–100 字符，自动去除首尾空白字符。
- `backend/src/character_app/domain/ports.py`：扩展 `CatalogRepository` 契约。
- `backend/src/character_app/adapters/storage/sqlite.py`：实现 `rename_conversation` 数据库操作，更新 `title`、自增 `version` 与刷新 `updated_at`。
- `backend/src/character_app/api/routes.py`：暴露路由并映射异常。
- `backend/tests/test_catalog.py`：新增 71 项自动化测试并通过。

### 2. 客户端数据层（Android Kotlin / Room / Net）

- `android/core/domain/src/main/kotlin/com/character/app/domain/Models.kt`：`Conversation` 增加 `version: Int = 1`。
- `android/core/domain/src/main/kotlin/com/character/app/domain/ReliableBackend.kt`：增加 `renameConversation` 幂等保护调用。
- `android/core/data/src/main/kotlin/com/character/app/data/HttpBackend.kt`：实现底层 HTTP `PATCH` 请求。
- `android/core/data/src/main/kotlin/com/character/app/data/OfflineRepository.kt`：实现原子更新，重命名成功后直接刷新本地 Room 缓存与 Snapshot 快照。

### 3. 用户界面（Jetpack Compose）

- `RenameConversationDialog.kt`：暗黑透明质感对话框，提供字数校验（1–100 字）、取消与保存按键。
- **改名双入口**：
  1. **消息列表卡片**：右侧快捷按钮 `✎`，点击即呼出修改弹窗。
  2. **聊天页顶部胶囊**：角色名右侧 `✎` 按钮，直接修改当前活跃会话名称。
- **即时搜索（MessagesScreen）**：
  - 搜索栏输入文字即时过滤。
  - 支持按会话自定义标题、角色名称以及标签类别双向匹配。
  - 未匹配内容提供无结果空状态提示。

---

## 三、构建与自动化发布配置

- 新增 GitHub Actions 自动发布工作流：`.github/workflows/release.yml`。
- 触发方式：推送以 `v` 开头的 Git Tag（例如 `v0.6.3`），或在 Actions 页面手动运行。
- 构建环境：Ubuntu Latest + JDK 21，自动执行 `./gradlew assembleDebug` 并挂载发布 APK 至 GitHub Releases。
