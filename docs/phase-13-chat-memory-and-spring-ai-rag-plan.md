# CogniNote Agent 第十三阶段计划：SQLite 聊天记忆与 Spring AI RAG Advisor 重构

## Summary

第十三阶段实现聊天记忆持久化，并把当前手动拼接 `{context}` 的 RAG Prompt 改为 Spring AI `ChatClient + Advisor` 风格调用。

实施状态：已落地。SQLite 现在保存会话与消息全量历史；前端会话列表、历史详情、新建、切换、重命名、删除和清空消息都以后端 API 为事实来源。RAG 增强由 `CogninoteDocumentRetriever` 挂到 Spring AI `RetrievalAugmentationAdvisor`，聊天记忆由 `SQLiteChatMemory`、`ConversationMemorySnapshotService` 和 `CogninoteMemoryAdvisor` 承接。第十八阶段后，对话执行入口已从单一 Agent 升级为 `ChatAgentRouter + GeneralChatAgent + KnowledgeBaseChatAgent`，并通过 `chat_messages.agent_type` 隔离普通对话和知识库模式的记忆污染。

本阶段明确不使用“固定最近 20 条消息”作为记忆策略。SQLite 保存全量会话历史；模型输入采用“会话摘要 + token 预算内最近原文消息”的分层记忆，避免长会话几十条消息后丢失早期关键上下文。

## Key Changes

- 聊天数据持久化：
  - 新增 `chat_sessions`，保存会话标题、摘要、摘要覆盖到的消息序号、检索设置、创建/更新时间和软删除状态。
  - 新增 `chat_messages`，保存消息顺序、role、content、status、requestId、retrievalMode、sources JSON、token 估算和创建时间。
  - 用户首次发送消息时创建或更新会话；assistant 流式完成后保存完整回答；用户显式停止时保存部分回答并标记 `STOPPED`。

- 分层聊天记忆：
  - SQLite 保存全量消息，不删除历史上下文。
  - 新增 `ConversationMemorySnapshotService`，读取会话摘要和最近原文消息，并按 token 预算生成本轮模型输入。
  - 默认至少保留最近 8 条原文消息；超过预算的更早消息进入滚动摘要。
  - 摘要内容写回 `chat_sessions.summary`，摘要覆盖序号写入 `summary_message_sequence`。
  - 固定条数只作为最低保护窗口，不作为唯一上下文策略。

- Spring AI 记忆接入：
  - 实现 SQLite 版 Spring AI `ChatMemory` 适配层。
  - 新增 `CogninoteMemoryAdvisor`，通过 Spring AI Advisor API 注入会话摘要和最近原文消息。
  - Advisor 使用 `ChatMemory.CONVERSATION_ID` 绑定当前会话。
  - 不引入 Redis，不照搬 `tj-aigc` 的存储实现，只参考其 `ChatMemory + Advisor + conversationId` 设计。

- Spring AI RAG Advisor 重构：
  - 删除手动 `{context}` 拼接路径，`PromptAssembler` 只提供 system prompt、当前 user prompt 和空上下文提示。
  - 新增 `CogninoteDocumentRetriever`，通过 Spring AI RAG API 调用现有 `KnowledgeStore.search()`。
  - `RetrievalAugmentationAdvisor` 承接 RAG 增强，保留现有 Lucene/SQLite 搜索、HYBRID/VECTOR/KEYWORD、Embedding 降级和目录启停规则。
  - 检索结果转换为 Spring AI `Document`，metadata 保留非空的 `chunkId/documentId/fileName/sourcePath/heading/pageNumber/score`，用于 RAG Advisor 处理；SSE `meta.sources` 仍负责前端引用来源回显。
  - Spring AI `Document.metadata` 不允许 `null` value，`heading/pageNumber` 等可选字段缺失时必须省略，避免 RAG 流式链路因 `metadata cannot have null values` 中断。
  - `useKnowledgeBase=false` 时只挂 memory advisor，不挂 RAG advisor，实现纯模型对话。

- 前端会话改为后端事实来源：
  - 左侧会话列表从 `GET /api/chat/sessions` 加载，按更新时间倒序展示。
  - 支持新建、切换、重命名、删除、清空会话消息。
  - 刷新页面后恢复历史消息、sources、retrievalMode 和 stopped/error 状态。
  - 停止按钮继续调用 `POST /api/chat/stream/{requestId}/cancel`。

## Public API / Schema Changes

新增 API：

```text
GET    /api/chat/sessions
POST   /api/chat/sessions
GET    /api/chat/sessions/{conversationId}
PATCH  /api/chat/sessions/{conversationId}
DELETE /api/chat/sessions/{conversationId}
DELETE /api/chat/sessions/{conversationId}/messages
```

扩展 `POST /api/chat/stream`：

```json
{
  "requestId": "request-xxx",
  "conversationId": "conversation-xxx",
  "question": "问题",
  "useKnowledgeBase": true,
  "mode": "HYBRID",
  "topK": 8
}
```

SSE `meta` 继续返回：

```json
{
  "requestId": "...",
  "conversationId": "...",
  "retrievalMode": "HYBRID",
  "sources": []
}
```

新增 SQLite 表：

```sql
CREATE TABLE IF NOT EXISTS chat_sessions (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    summary TEXT,
    summary_message_sequence INTEGER NOT NULL DEFAULT 0,
    use_knowledge_base INTEGER NOT NULL DEFAULT 1,
    retrieval_mode TEXT NOT NULL DEFAULT 'HYBRID',
    top_k INTEGER NOT NULL DEFAULT 8,
    deleted INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    message_sequence INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL,
    request_id TEXT,
    agent_type TEXT,
    retrieval_mode TEXT,
    sources_json TEXT,
    token_estimate INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);
```

`agent_type` 是第十八阶段新增字段。旧 SQLite 文件启动时由 schema 初始化自动补列；旧 assistant 消息读取时会按 `retrieval_mode` 推断 Agent，旧 user 消息按中性历史处理。

## Runtime Flow

```text
POST /api/chat/stream
  ↓
ChatController
  ↓
AgentExecutionService
  ↓
ChatAgentRouter
  ├─ useKnowledgeBase=false -> GeneralChatAgent
  │    ├─ ensureSession + appendUserMessage
  │    ├─ active CHAT runtime
  │    └─ CogninoteMemoryAdvisor
  └─ useKnowledgeBase=true/null -> KnowledgeBaseChatAgent
       ├─ ensureSession + appendUserMessage
       ├─ active CHAT runtime
       ├─ CogninoteMemoryAdvisor
       └─ CogninoteDocumentRetriever + RetrievalAugmentationAdvisor
  ↓
Spring AI stream()
  ↓
SSE meta/delta/done/error
  ↓
assistant DONE / STOPPED / ERROR 写入 chat_messages
```

普通浏览器刷新、切页或 SSE 连接断开不是用户停止。后端会继续消费模型流到完成，并保存完整 assistant 消息。只有用户显式点击停止并调用取消接口时，才中断模型订阅并保存部分 assistant 为 `STOPPED`。

## Test Plan

- 后端：
  - `mvn test`
  - 覆盖 schema 初始化、会话 CRUD、消息写入、消息顺序、软删除和清空消息。
  - 覆盖长会话：全量历史留在 SQLite，模型输入包含摘要和 token 预算内最近消息。
  - 覆盖纯模型对话：`useKnowledgeBase=false` 不检索知识库，但能使用会话记忆。
  - 覆盖 RAG 对话：通过 Spring AI Advisor 调用检索适配器，不再手动拼接 `{context}`。
  - 覆盖停止生成：部分 assistant 消息落库并标记 `STOPPED`。
  - 覆盖普通 SSE 断开：后端继续生成到完成并保存完整 assistant 消息。

- 前端：
  - `npm --prefix cogniNote-agent-front run build`
  - 会话列表、历史详情、新建、切换、重命名、删除、清空消息可用。
  - 刷新后能恢复历史对话和引用来源。
  - RAG 对话和纯模型对话都能流式显示并落库。
  - 第十二阶段 Markdown 空白保留和停止按钮行为不回退。

- 不做桌面整包验证。

## Troubleshooting Notes

- `metadata cannot have null values`：说明传给 Spring AI `Document` 的 metadata 包含了 `null`。检查 `CogninoteDocumentRetriever`，可选字段应省略而不是写入 `null`。
- `Java vector incubator module is not readable`：这是 Lucene 向量性能提示，不是 RAG 失败原因。需要极致向量性能时可评估 JVM 启动参数 `--add-modules jdk.incubator.vector`，但本阶段不强制启用。

已执行验证：

```text
mvn test
npm --prefix cogniNote-agent-front run build
```

`mvn clean test` 在 Windows 上可能被正在运行的 `target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 锁定；本阶段不要求清理桌面 app-image 后再验证。

## Assumptions

- 第十三阶段做短期/中期会话记忆，不做用户画像、长期向量化历史记忆和跨会话知识沉淀。
- SQLite 是会话和消息事实来源；Spring AI `ChatMemory`/Advisor 是调用层适配。
- 现有 Lucene/SQLite 知识库不迁移到外部向量库。
- RAG 增强必须经过 Spring AI Advisor/ChatClient API，不再手动把知识库上下文拼进 user prompt。
- 默认 token 预算配置由 `application.yaml` 管理，后续可在设置页暴露。
