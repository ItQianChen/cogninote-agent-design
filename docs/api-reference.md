# CogniNote API 参考

本文档记录当前后端对外暴露的 HTTP API。普通 JSON API 使用统一响应格式；RAG 对话流式接口使用 SSE，不做 JSON 包装。

## 统一响应格式

普通 JSON API 返回 `ApiResponse<T>`：

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": {},
  "timestamp": 1780000000000
}
```

错误响应保持同形状：

```json
{
  "success": false,
  "code": "BAD_REQUEST",
  "message": "请求参数不合法",
  "data": null,
  "timestamp": 1780000000000
}
```

例外：

- `POST /api/chat/stream` 返回 `text/event-stream`，不包装。
- `POST /api/chat/stream/{requestId}/cancel` 是普通 JSON API，用于用户显式停止当前模型流。
- `/api/chat/sessions...` 是普通 JSON API，会话和消息都以 SQLite 为事实来源。
- `DELETE /api/documents/{id}` 删除成功时返回 `204 No Content`。
- `PATCH /api/knowledge-folders/{id}/enabled` 和 `DELETE /api/knowledge-folders/{id}` 成功时返回 `204 No Content`。

## 系统状态

```text
GET /api/system/status
```

返回应用名、版本、运行状态和当前数据目录。

## 文档

### 查询文档列表

```text
GET /api/documents
```

返回已导入文档列表，按更新时间倒序排列。

### 导入目录

```text
POST /api/documents/ingest
```

请求体：

```json
{
  "folderPath": "D:/notes",
  "recursive": true
}
```

导入成功后会写入 SQLite，并同步更新 Lucene 索引。导入失败的文件会返回失败摘要，不删除用户原始文件。

旧前端或临时脚本仍可使用该接口；当前知识库页面优先使用 `/api/knowledge-folders` 目录管理接口。

### 删除文档

```text
DELETE /api/documents/{id}
```

删除 SQLite 中的文档记录和 chunks，并清理 Lucene 中对应索引。不会删除用户原始文件。

## 知识库目录

第十阶段开始，前端知识库页以“知识库目录”为主。SQLite 保存目录记录和文档归属，Lucene 只作为可重建索引。

### 查询知识库目录

```text
GET /api/knowledge-folders
```

返回目录列表、每个目录的启用状态、统计信息和文档摘要。历史散落文档会返回在 `unassignedDocuments` 中。

### 导入知识库目录

```text
POST /api/knowledge-folders/import
```

请求体：

```json
{
  "folderPath": "D:/notes",
  "recursive": true
}
```

后端会创建或更新目录记录，扫描支持的文件，写入/更新 `documents` 和 `chunks`，并同步写入 Lucene。重复导入同一路径会复用已有目录记录。

### 重建单个目录索引

```text
POST /api/knowledge-folders/{id}/rebuild
```

只重新扫描该目录并重建该目录下的 Lucene 条目，不影响其他目录。目录重建失败的文件会返回失败摘要并写日志；已有 SQLite 解析结果尽量保留，避免一次重建破坏旧知识库数据。若本地目录中某个旧文件已被删除，重建会删除应用内对应文档/chunks/索引记录，但不触碰用户文件系统。

响应体 `data` 同时包含扫描和索引统计：

```json
{
  "scannedCount": 3,
  "parsedCount": 1,
  "skippedCount": 1,
  "failedCount": 1,
  "failures": [
    {
      "sourcePath": "D:/notes/broken.pdf",
      "message": "Parsed document contains no usable text"
    }
  ],
  "indexedDocumentCount": 2,
  "indexedChunkCount": 18,
  "failedDocumentCount": 0,
  "durationMs": 120
}
```

### 启用或停用目录

```text
PATCH /api/knowledge-folders/{id}/enabled
```

请求体：

```json
{
  "enabled": false
}
```

停用目录会清理该目录的 Lucene 条目并把相关文档标记为未索引，使搜索/RAG 立即不再命中该目录；SQLite 目录、文档和 chunks 会保留。重新启用时，后端会从 SQLite chunks 恢复该目录索引。

### 删除目录

```text
DELETE /api/knowledge-folders/{id}
```

删除目录记录、关联文档、chunks 和 Lucene 条目。不会删除用户本机原始文件。

## 检索与索引

### 索引状态

```text
GET /api/index/status
```

返回 SQLite 文档统计、Lucene chunk 数量和索引状态。

### 重建索引

```text
POST /api/index/rebuild
```

从 SQLite 中已解析的 chunks 全量重建 Lucene 索引。全量重建只处理启用目录和未归属文档；停用目录不会重新进入索引。Lucene 是可重建索引，不是业务事实来源。

### 搜索

```text
POST /api/search
```

请求体示例：

```json
{
  "query": "如何打包桌面应用？",
  "mode": "HYBRID",
  "topK": 8
}
```

`mode` 支持：

- `KEYWORD`
- `VECTOR`
- `HYBRID`

Embedding 不可用时，向量检索和混合检索可能降级或返回明确错误，具体行为由调用场景决定。

## 模型配置

第八阶段开始，对话模型和 Embedding 模型分开维护。普通 CRUD 兼容接口使用 `/api/model-configs`；设置页使用 `/api/model-configs/settings...` 快照接口，避免前端自己拼装 active、列表和右侧表单。旧 `/api/model-config` 仅作为过渡兼容接口保留。

### 查询配置列表

```text
GET /api/model-configs?role=CHAT
GET /api/model-configs?role=EMBEDDING
```

返回指定类型的配置列表，active 配置排在前面。

### 查询 active 配置

```text
GET /api/model-configs/active
```

返回当前 active Chat 和 active Embedding：

```json
{
  "chat": {
    "id": "active-chat",
    "role": "CHAT",
    "provider": "DASHSCOPE",
    "displayName": "DashScope Chat",
    "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
    "apiKeyConfigured": true,
    "apiKey": "sk-...",
    "modelName": "qwen-plus",
    "temperature": 0.7,
    "defaultTopK": 8,
    "active": true
  },
  "embedding": {
    "id": "active-embedding",
    "role": "EMBEDDING",
    "provider": "DASHSCOPE",
    "displayName": "DashScope Embedding",
    "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
    "apiKeyConfigured": true,
    "apiKey": "sk-...",
    "modelName": "text-embedding-v4",
    "embeddingDimensions": 1024,
    "active": true
  }
}
```

### 设置页快照

```text
GET /api/model-configs/settings?role=CHAT
GET /api/model-configs/settings?role=EMBEDDING
```

返回设置页一次渲染所需的完整快照：

```json
{
  "active": {
    "chat": {
      "id": "active-chat",
      "role": "CHAT",
      "displayName": "DashScope Chat",
      "modelName": "qwen-plus",
      "active": true
    },
    "embedding": {
      "id": "active-embedding",
      "role": "EMBEDDING",
      "displayName": "DashScope Embedding",
      "modelName": "text-embedding-v4",
      "embeddingDimensions": 1024,
      "active": true
    }
  },
  "role": "CHAT",
  "configs": [],
  "selectedConfig": {}
}
```

- `active`：顶部 Active Chat / Active Embedding 卡片。
- `configs`：当前 role 左侧配置列表。
- `selectedConfig`：右侧表单默认展示的配置，优先为当前 role active 配置。

设置页新增、修改、删除、启用操作都返回同样的快照格式：

```text
POST   /api/model-configs/settings/configs
PUT    /api/model-configs/settings/configs/{id}
DELETE /api/model-configs/settings/configs/{id}
POST   /api/model-configs/settings/configs/{id}/activate
```

### 新建配置

```text
POST /api/model-configs
```

Chat 请求体示例：

```json
{
  "role": "CHAT",
  "provider": "OPENAI_COMPATIBLE",
  "displayName": "Local Chat",
  "baseUrl": "http://127.0.0.1:11434/v1",
  "apiKey": "sk-...",
  "modelName": "qwen-plus",
  "temperature": 0.7,
  "defaultTopK": 8
}
```

Embedding 请求体示例：

```json
{
  "role": "EMBEDDING",
  "provider": "OPENAI_COMPATIBLE",
  "displayName": "Local Embedding",
  "baseUrl": "http://127.0.0.1:11434/v1",
  "apiKey": "sk-...",
  "modelName": "text-embedding-v4",
  "embeddingDimensions": 1024
}
```

### 更新配置

```text
PUT /api/model-configs/{id}
```

请求体同新建配置。保存时 API Key 留空表示复用该配置已保存 key，避免用户每次修改模型参数都重新输入密钥。

### 删除配置

```text
DELETE /api/model-configs/{id}
```

旧 CRUD 删除接口删除后会确保该 role 仍存在 active 配置。设置页删除接口允许删除唯一配置，但会自动补一条默认 active 草稿，并返回新的设置页快照，避免前端进入无 active 的坏状态。

### 激活配置

```text
POST /api/model-configs/{id}/activate
```

同一类型最多只有一个 active 配置。激活 Chat 配置不会影响 Embedding 配置。

### 测试连接

```text
POST /api/model-configs/test
```

使用配置草稿测试模型是否可用。Chat 会调用 LLM 连接测试；Embedding 第一版不主动发 embedding 请求，避免保存前产生不必要成本或维度副作用。

### 获取模型列表

```text
POST /api/model-configs/models
```

使用配置草稿获取模型列表。DashScope 走百炼兼容模型列表端点；OpenAI-compatible 走用户 `Base URL + /models`。

### 旧兼容接口

```text
GET /api/model-config
PUT /api/model-config
POST /api/model-config/test
POST /api/model-config/models
```

旧接口暂时保留一个阶段。`GET /api/model-config` 返回 active Chat 和 active Embedding 的组合视图；`PUT /api/model-config` 会把旧格式请求拆成 Chat 和 Embedding 两条配置保存。

旧请求体示例：

```json
{
  "provider": "OPENAI_COMPATIBLE",
  "displayName": "Local Gateway",
  "baseUrl": "http://127.0.0.1:11434/v1",
  "apiKey": "sk-...",
  "chatModel": "qwen-plus",
  "embeddingModel": "text-embedding-v4",
  "embeddingDimensions": 1024,
  "temperature": 0.7,
  "topK": 8
}
```

## 聊天会话

第十三阶段开始，左侧会话列表和历史消息都由后端 SQLite 提供。前端刷新后通过会话 API 恢复消息、引用来源、检索模式和 stopped/error 状态。

### 查询会话列表

```text
GET /api/chat/sessions
```

返回按 `updatedAt` 倒序排列的会话摘要。摘要不携带完整消息，只包含 `messageCount`：

```json
[
  {
    "id": "conversation-1",
    "title": "如何打包？",
    "summary": null,
    "useKnowledgeBase": true,
    "mode": "HYBRID",
    "topK": 8,
    "createdAt": 1780000000000,
    "updatedAt": 1780000000000,
    "messageCount": 2,
    "messages": []
  }
]
```

### 创建会话

```text
POST /api/chat/sessions
```

请求体可为空，也可提供默认检索设置：

```json
{
  "title": "新对话",
  "useKnowledgeBase": true,
  "mode": "HYBRID",
  "topK": 8
}
```

### 查询会话详情

```text
GET /api/chat/sessions/{conversationId}
```

返回会话详情和完整消息列表。assistant 消息会带回 `sources`，用于刷新页面后恢复引用来源。

### 更新会话

```text
PATCH /api/chat/sessions/{conversationId}
```

可更新标题和该会话默认检索设置：

```json
{
  "title": "桌面打包问题",
  "useKnowledgeBase": true,
  "mode": "KEYWORD",
  "topK": 5
}
```

### 删除会话

```text
DELETE /api/chat/sessions/{conversationId}
```

软删除会话，不影响知识库文档、chunks、索引或用户原始文件。

### 清空会话消息

```text
DELETE /api/chat/sessions/{conversationId}/messages
```

删除该会话下的消息，同时清空会话摘要和摘要覆盖序号，返回清空后的会话详情。

## RAG / 纯模型流式对话

```text
POST /api/chat/stream
```

请求体：

```json
{
  "requestId": "前端生成的请求 ID，可省略",
  "conversationId": "conversation-xxx，可省略",
  "question": "这个项目如何打包？",
  "useKnowledgeBase": true,
  "topK": 8,
  "mode": "HYBRID"
}
```

`requestId`、`conversationId`、`topK`、`mode` 和 `useKnowledgeBase` 可省略。默认使用后端生成的 `requestId/conversationId`、active Chat 配置中的 `defaultTopK`、`HYBRID` 和 `useKnowledgeBase=true`。前端需要支持停止生成时，应在请求体中传入稳定 `requestId`，然后调用取消接口。

SSE 事件格式：

```text
event: meta
data: {"requestId":"...","conversationId":"...","retrievalMode":"HYBRID","sources":[...]}

event: delta
data: {"text":"..."}

event: done
data: {"usage":null}

event: error
data: {"message":"..."}
```

事件顺序通常为：

```text
meta -> delta -> done
```

异常时输出 `error`。如果 `HYBRID` 或 `VECTOR` 因 Embedding 不可用失败，RAG 服务会自动降级到 `KEYWORD`，并在 `meta.retrievalMode` 中返回实际检索模式。`useKnowledgeBase=false` 时路由到 `GENERAL_CHAT` 普通对话 Agent，不挂 RAG Advisor，`retrievalMode` 为 `null`、`sources` 为空，只注入模式隔离后的会话记忆。`useKnowledgeBase=true` 或省略时路由到 `KNOWLEDGE_BASE` 知识库 Agent。

重要约束：

- SQLite 会保存全量会话历史。模型输入由“会话摘要 + token 预算内最近原文消息”组成；默认至少保留最近 8 条原文消息，但不会把固定条数作为唯一记忆策略。
- 同一会话可以在普通对话和知识库模式之间切换。后端会用 `agent_type` 标记消息，并在模型输入里隔离跨 Agent 历史：上一种 Agent 的拒答规则、引用规则和系统规则不能覆盖当前 Agent。
- RAG 不再手动把 `{context}` 拼进 user prompt。知识库片段通过 Spring AI `RetrievalAugmentationAdvisor` 和 `CogninoteDocumentRetriever` 注入。
- Spring AI `Document.metadata` 不允许出现 `null`。后端转换 RAG sources 时会省略缺失的 `heading/pageNumber` 等可选字段，前端仍以 SSE `meta.sources` 作为引用来源展示事实来源。
- `delta.text` 是模型原始流式文本增量，可能只包含一个空格、换行或缩进。客户端和服务端都不能对它做 `trim()`、`trimStart()` 或 `isBlank()` 过滤，否则 Markdown 标题、列表、代码块和表格可能被破坏。
- 前端手写 SSE parser 时，`data:` 后最多只移除一个协议分隔空格；内容本身的前导空白必须保留。
- `POST /api/chat/stream` 已经写出 `text/event-stream` 后，错误不能再按 JSON `ApiResponse` 写回。能进入业务流的错误应发送 SSE `error` 事件；连接关闭或容器异常只能关闭响应。
- 普通浏览器刷新、切页或 SSE 连接断开不代表用户停止生成。后端仍会消费模型流到结束并在完成后保存完整 assistant 消息。
- 用户显式停止时，取消接口会中断模型订阅，并把已生成的 assistant 片段保存为 `STOPPED`。

### 取消流式生成

```text
POST /api/chat/stream/{requestId}/cancel
```

取消接口只用于用户点击停止按钮时中断对应模型订阅。成功响应为普通 `ApiResponse<Boolean>`：

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": true,
  "timestamp": 1780000000000
}
```

`data=true` 表示找到并取消了仍在运行的流；`data=false` 表示该请求已结束、未注册或已经被取消。
