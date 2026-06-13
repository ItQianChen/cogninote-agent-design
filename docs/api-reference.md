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
- `GET /api/knowledge-graphs/runs/{runId}/events` 返回 `text/event-stream`，不包装。
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

修改 Analyzer、BM25 参数、代码索引文本策略、Embedding 模型或 Embedding 维度后，都需要执行全量重建索引。若旧版本导入时已经把代码块缩进清洗丢失，只重建 Lucene 无法恢复格式，需要从原始文件重新导入。

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

搜索结果中的 `score`、`keywordScore`、`vectorScore` 语义如下：

- `KEYWORD`：`score` 和 `keywordScore` 为 Lucene BM25 原始分数，`vectorScore` 为空。
- `VECTOR`：`score` 和 `vectorScore` 为向量召回原始分数，`keywordScore` 为空。
- `HYBRID`：`score` 为加权 RRF 融合分数，`keywordScore` / `vectorScore` 分别保留原始 BM25 / Vector 分数。

中文正文使用中文 Analyzer；代码块、类名、函数名、变量名、路径、异常名和 Mermaid/PlantUML 等流程图节点会派生到代码检索字段。REST 请求和响应结构保持兼容。

实用验证示例：

```json
{
  "query": "知识库重建索引",
  "mode": "KEYWORD",
  "topK": 8
}
```

`KEYWORD` 可用于验证中文 BM25 效果，即使当前没有可用 Embedding 配置也能运行。适合测试 `桌面打包失败`、`模型配置`、`向量检索` 这类中文短语。

```json
{
  "query": "chat agent router",
  "mode": "KEYWORD",
  "topK": 8
}
```

代码检索字段会拆分 camelCase / snake_case / kebab-case，并保留类名、函数名、变量名、异常名和路径片段。适合测试 `ChatAgentRouter`、`snake case`、`foo bar`、`DataIntegrityViolationException`、`ChatSessionMapper.xml`、`INSERT OR IGNORE` 等查询。

```json
{
  "query": "用户提问 重建索引",
  "mode": "HYBRID",
  "topK": 8
}
```

`HYBRID` 适合在已配置 Embedding 后验证混合召回。它会把中文 BM25、代码字段命中和向量语义召回一起纳入 RRF 排序；流程图中的 Mermaid / PlantUML 图类型、节点文本和边关系文本也可以被召回。

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
    "contextWindowTokens": 128000,
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
      "contextWindowTokens": 128000,
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
  "selectedConfig": {
    "contextWindowTokens": 128000
  }
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
  "defaultTopK": 8,
  "contextWindowTokens": 128000
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
  "topK": 8,
  "contextWindowTokens": 128000
}
```

`contextWindowTokens` 只对 Chat 配置生效，默认 `128000`，合法范围为 `1024` 到 `2000000`。Embedding 配置会返回 `null`，不会参与聊天上下文预算。

## 聊天会话

第十三阶段开始，左侧会话列表和历史消息都由后端 SQLite 提供。前端刷新后通过会话 API 恢复消息、引用来源、检索模式和 stopped/error 状态。

### 查询会话列表

```text
GET /api/chat/sessions
```

返回按 `updatedAt` 倒序排列的会话摘要。摘要不携带完整消息，只包含 `messageCount`，并携带当前上下文占用 `contextUsage`：

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
    "contextUsage": {
      "contextWindowTokens": 128000,
      "usedTokens": 680,
      "availableTokens": 127320,
      "usageRatio": 0.0053,
      "compressed": false,
      "summaryTokens": 0,
      "recentMessageTokens": 680,
      "recentMessageCount": 2,
      "totalMessageCount": 2,
      "summaryMessageSequence": 0,
      "estimationMethod": "jtokkit:o200k_base"
    },
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

返回会话详情、完整消息列表和 `contextUsage`。assistant 消息会带回 `sources`，用于刷新页面后恢复引用来源；`contextUsage` 会按当前 active Chat 配置重新估算上下文窗口、摘要和最近原文消息占用。

`ChatContextUsageResponse` 字段说明：

| 字段 | 说明 |
| --- | --- |
| `contextWindowTokens` | 当前 active Chat 配置的上下文窗口，默认 `128000` |
| `usedTokens` | 本轮展示口径下已使用 token，压缩后按“摘要 + 最近原文消息”计算 |
| `availableTokens` | `contextWindowTokens - usedTokens` 的非负值 |
| `usageRatio` | 使用比例，范围 `0` 到 `1` |
| `compressed` | 当前会话是否已有摘要压缩 |
| `summaryTokens` | 会话摘要估算 token |
| `recentMessageTokens` | 最近原文消息估算 token |
| `recentMessageCount` | 当前纳入上下文展示口径的最近原文消息数 |
| `totalMessageCount` | 会话总消息数 |
| `summaryMessageSequence` | 摘要已覆盖到的消息序号 |
| `estimationMethod` | 估算方式，例如 `jtokkit:o200k_base` 或 fallback |

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

物理删除该会话和会话下的聊天消息，不影响知识库文档、chunks、索引或用户原始文件。

### 清空会话消息

```text
DELETE /api/chat/sessions/{conversationId}/messages
```

删除该会话下的消息，同时清空会话摘要和摘要覆盖序号，返回清空后的会话详情。

## 聊天设置

第 23 阶段新增全局聊天设置 API。该设置保存在 SQLite `app_settings` 中，优先级高于环境变量；当前用于控制知识库模式下的追问补全策略。

### 查询聊天设置

```text
GET /api/chat/settings
```

响应：

```json
{
  "queryContextualizerMode": "AUTO"
}
```

### 保存聊天设置

```text
PUT /api/chat/settings
```

请求体：

```json
{
  "queryContextualizerMode": "OFF"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `queryContextualizerMode` | `AUTO`、`ALWAYS` 或 `OFF`。默认 `AUTO`。 |

模式语义：

| 模式 | 行为 |
| --- | --- |
| `AUTO` | 只有像省略、指代、动作型追问、英文领域切换或原问题检索较弱时才调用补全 Agent。 |
| `ALWAYS` | 保持第 21 阶段行为，知识库模式每轮都先判断是否需要补全。 |
| `OFF` | 完全关闭补全，始终使用用户原问题检索。 |

该设置只影响知识库检索 query，不会修改 `chat_messages.content` 中的用户原文，也不会影响 `useKnowledgeBase=false` 的纯模型对话。

## 知识图谱

知识图谱是知识库资料的派生物。后端基于已解析 chunks 调用 active Chat 模型抽取实体、关系和证据，写入 SQLite 图谱缓存，再生成思维导图和关系图视图。导入文档或重建 Lucene 索引不会自动重建图谱，必须由用户显式触发。

`scopeType` 支持：

| 值 | scopeId |
| --- | --- |
| `ALL` | 可省略，表示全库 |
| `KNOWLEDGE_FOLDER` | 必填，知识库目录 ID |
| `DOCUMENT` | 必填，文档 ID |

`viewType` 支持 `MINDMAP` 和 `GRAPH`。

### 重建图谱

```text
POST /api/knowledge-graphs/rebuild
```

请求体：

```json
{
  "scopeType": "KNOWLEDGE_FOLDER",
  "scopeId": "folder-xxx"
}
```

返回 `KnowledgeGraphRunResponse`。同一 scope 已有 `QUEUED` 或 `RUNNING` run 时不会新建任务，而是返回现有 run。

```json
{
  "runId": "run-xxx",
  "scopeType": "KNOWLEDGE_FOLDER",
  "scopeId": "folder-xxx",
  "status": "QUEUED",
  "modelConfigId": "chat-config-xxx",
  "promptVersion": "kg-extract-v1",
  "totalChunkCount": 0,
  "processedChunkCount": 0,
  "skippedChunkCount": 0,
  "extractedNodeCount": 0,
  "extractedEdgeCount": 0,
  "failedChunkCount": 0,
  "errorMessage": null,
  "startedAt": null,
  "completedAt": null,
  "createdAt": 1780000000000,
  "updatedAt": 1780000000000
}
```

### 查询图谱状态

```text
GET /api/knowledge-graphs/status?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx
```

返回 scope 名称、最新 run、节点/边数量和视图是否已生成：

```json
{
  "scopeType": "KNOWLEDGE_FOLDER",
  "scopeId": "folder-xxx",
  "scopeName": "项目资料",
  "latestRun": {
    "runId": "run-xxx",
    "scopeType": "KNOWLEDGE_FOLDER",
    "scopeId": "folder-xxx",
    "status": "COMPLETED",
    "modelConfigId": "chat-config-xxx",
    "promptVersion": "kg-extract-v1",
    "totalChunkCount": 120,
    "processedChunkCount": 120,
    "skippedChunkCount": 80,
    "extractedNodeCount": 42,
    "extractedEdgeCount": 31,
    "failedChunkCount": 0,
    "errorMessage": null,
    "startedAt": 1780000000000,
    "completedAt": 1780000005000,
    "createdAt": 1780000000000,
    "updatedAt": 1780000005000
  },
  "nodeCount": 42,
  "edgeCount": 31,
  "mindmapReady": true,
  "graphReady": true,
  "generatedAt": 1780000000000
}
```

### 订阅图谱生成事件

```text
GET /api/knowledge-graphs/runs/{runId}/events
```

该接口返回 SSE，不使用 `ApiResponse` 包装。事件包含当前快照、开始、进度、取消请求、视图生成、完成、失败和取消：

```text
event: graph-run-snapshot
data: {"runId":"run-xxx","status":"RUNNING",...}

event: graph-run-started
data: {"runId":"run-xxx","status":"RUNNING","stage":"EXTRACTING","totalChunkCount":120,"processedChunkCount":0,"skippedChunkCount":0,"failedChunkCount":0}

event: graph-run-progress
data: {"runId":"run-xxx","status":"RUNNING","stage":"EXTRACTING","totalChunkCount":120,"processedChunkCount":30,"skippedChunkCount":12,"failedChunkCount":0}

event: graph-run-cancel-requested
data: {"runId":"run-xxx"}

event: graph-run-view-ready
data: {"runId":"run-xxx","viewType":"MINDMAP"}

event: graph-run-completed
data: {"runId":"run-xxx","status":"COMPLETED","nodeCount":42,"edgeCount":31}

event: graph-run-failed
data: {"runId":"run-xxx","status":"FAILED","message":"..."}

event: graph-run-cancelled
data: {"runId":"run-xxx","status":"CANCELLED"}
```

客户端 SSE 断线后应调用 `GET /api/knowledge-graphs/runs/{runId}` 或 `GET /api/knowledge-graphs/status` 恢复快照。

### 查询 run

```text
GET /api/knowledge-graphs/runs/{runId}
```

返回单个 `KnowledgeGraphRunResponse`。

### 取消 run

```text
POST /api/knowledge-graphs/runs/{runId}/cancel
```

返回普通 `ApiResponse<Boolean>`。`true` 表示 run 仍在 `QUEUED/RUNNING` 且已登记取消；`false` 表示 run 已进入终态。取消只在抽取阶段停止后续模型调用，已写入的抽取缓存保留，旧图谱视图不删除。

### 查询图谱视图

```text
GET /api/knowledge-graphs/view?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx&viewType=MINDMAP
GET /api/knowledge-graphs/view?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx&viewType=GRAPH
```

`MINDMAP` 视图 payload：

```json
{
  "viewType": "MINDMAP",
  "payload": {
    "viewType": "MINDMAP",
    "markdown": "# 项目资料\n\n## README.md\n\n### 架构\n#### CogniNote [PRODUCT] x2\n",
    "root": {
      "id": "scope",
      "label": "项目资料",
      "type": "SCOPE"
    },
    "documents": [
      {
        "id": "doc-xxx",
        "label": "README.md",
        "fileName": "README.md",
        "headings": [
          {
            "id": "doc-xxx::heading::架构",
            "label": "架构",
            "entities": [
              {
                "id": "node-xxx",
                "label": "CogniNote",
                "type": "PRODUCT",
                "count": 2
              }
            ]
          }
        ]
      }
    ]
  },
  "generatedFromRunId": "run-xxx",
  "createdAt": 1780000000000,
  "updatedAt": 1780000000000
}
```

`markdown` 字段会继续保留，旧缓存只有 `markdown` 时仍可被前端兼容解析。第 26 阶段新增的 `root` 和 `documents` 用于结构化思维导图渲染，不改变 `viewType=MINDMAP` 的请求方式。

`GRAPH` 视图 payload：

```json
{
  "viewType": "GRAPH",
  "payload": {
    "viewType": "GRAPH",
    "nodeLimit": 100,
    "totalNodeCount": 42,
    "totalEdgeCount": 31,
    "hiddenNodeCount": 0,
    "nodeTypeCounts": {
      "PRODUCT": 1,
      "TECHNOLOGY": 1
    },
    "relationTypeCounts": {
      "USES": 1
    },
    "nodes": [
      {
        "id": "node-xxx",
        "label": "CogniNote",
        "type": "PRODUCT",
        "degree": 3,
        "mentionCount": 5,
        "confidence": 0.92
      }
    ],
    "edges": [
      {
        "id": "edge-xxx",
        "source": "node-xxx",
        "target": "node-yyy",
        "sourceLabel": "CogniNote",
        "targetLabel": "Lucene",
        "label": "USES",
        "weight": 2,
        "confidence": 0.88
      }
    ]
  },
  "generatedFromRunId": "run-xxx",
  "createdAt": 1780000000000,
  "updatedAt": 1780000000000
}
```

`nodes` / `edges` 的基础字段保持兼容。`nodeTypeCounts`、`relationTypeCounts`、`hiddenNodeCount`、`sourceLabel` 和 `targetLabel` 是第 26 阶段新增的展示辅助字段，用于图例、筛选、Inspector 和列表视图。

### 查询证据

```text
GET /api/knowledge-graphs/nodes/{nodeId}/evidence
GET /api/knowledge-graphs/edges/{edgeId}/evidence
```

返回节点或关系的证据列表，包含 quote、chunk、文档元数据和图谱对象摘要：

```json
[
  {
    "id": "evidence-xxx",
    "runId": "run-xxx",
    "nodeId": "node-xxx",
    "edgeId": null,
    "documentId": "doc-xxx",
    "chunkId": "chunk-xxx",
    "quote": "CogniNote Agent 是一个本地优先的个人知识库问答应用。",
    "confidence": 0.92,
    "createdAt": 1780000000000,
    "fileName": "README.md",
    "sourcePath": "D:/notes/README.md",
    "heading": "简介",
    "pageNumber": null,
    "chunkIndex": 0,
    "nodeDisplayName": "CogniNote",
    "nodeType": "PRODUCT",
    "edgeRelationType": null,
    "edgeSourceName": null,
    "edgeTargetName": null
  }
]
```

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
data: {"requestId":"...","conversationId":"...","retrievalMode":"HYBRID","sources":[...],"contextUsage":{"contextWindowTokens":128000,"usedTokens":680,"availableTokens":127320,"usageRatio":0.0053,"compressed":false,"summaryTokens":0,"recentMessageTokens":680,"recentMessageCount":2,"totalMessageCount":2,"summaryMessageSequence":0,"estimationMethod":"jtokkit:o200k_base"}}

event: delta
data: {"text":"..."}

event: done
data: {"usage":null,"contextUsage":{"contextWindowTokens":128000,"usedTokens":1240,"availableTokens":126760,"usageRatio":0.0097,"compressed":false,"summaryTokens":0,"recentMessageTokens":1240,"recentMessageCount":4,"totalMessageCount":4,"summaryMessageSequence":0,"estimationMethod":"jtokkit:o200k_base"}}

event: error
data: {"message":"..."}
```

事件顺序通常为：

```text
meta -> delta -> done
```

异常时输出 `error`，事件顺序为 `meta -> delta -> error`。客户端只有收到 `done` 或 `error` 终止事件时，才能认为本轮 SSE 流有明确结论；如果连接关闭但没有终止事件，应按回答未完成处理。如果 `HYBRID` 或 `VECTOR` 因 Embedding 不可用失败，RAG 服务会自动降级到 `KEYWORD`，并在 `meta.retrievalMode` 中返回实际检索模式。`useKnowledgeBase=false` 时路由到 `GENERAL_CHAT` 普通对话 Agent，不挂 RAG Advisor，`retrievalMode` 为 `null`、`sources` 为空，只注入模式隔离后的会话记忆。`useKnowledgeBase=true` 或省略时路由到 `KNOWLEDGE_BASE` 知识库 Agent；知识库模式会按 `queryContextualizerMode` 决定是否内部调用追问补全 Agent，必要时把历史主题补进检索 query，但请求体、SSE `meta` 和用户消息内容都不变。

重要约束：

- SQLite 会保存全量会话历史。模型输入由“会话摘要 + token 预算内最近原文消息”组成；历史预算优先来自 active Chat 配置的 `contextWindowTokens`，默认 `128000`，并最多使用约 80% 窗口；默认至少保留最近 8 条原文消息，但不会把固定条数作为唯一记忆策略。
- 同一会话可以在普通对话和知识库模式之间切换。后端会用 `agent_type` 标记消息，并在模型输入里隔离跨 Agent 历史：上一种 Agent 的拒答规则、引用规则和系统规则不能覆盖当前 Agent。
- 知识库模式下，后端可能按 `AUTO/ALWAYS/OFF` 策略使用 active Chat 模型对省略式追问生成内部 `retrievalQuery`。`AUTO` 本地打分器会综合短句、指代、省略补全、动作型请求、英文领域切换和完整问题反向信号；如果本地判断已触发但补全模型误判不改写，短动作型追问会用最近明确主题的用户问题构造本地兜底 query。这个 query 只用于检索，不写入 `chat_messages.content`，也不会通过 SSE 暴露；补全失败、非法 JSON 或 query 过长时会回退原问题检索。
- RAG 不再手动把 `{context}` 拼进 user prompt。知识库片段通过 Spring AI `RetrievalAugmentationAdvisor` 和 `CogninoteDocumentRetriever` 注入。
- Spring AI `Document.metadata` 不允许出现 `null`。后端转换 RAG sources 时会省略缺失的 `heading/pageNumber` 等可选字段，前端仍以 SSE `meta.sources` 作为引用来源展示事实来源。
- `delta.text` 是模型原始流式文本增量，可能只包含一个空格、换行或缩进。客户端和服务端都不能对它做 `trim()`、`trimStart()` 或 `isBlank()` 过滤，否则 Markdown 标题、列表、代码块和表格可能被破坏。
- 前端手写 SSE parser 时，`data:` 后最多只移除一个协议分隔空格；内容本身的前导空白必须保留。
- `POST /api/chat/stream` 已经写出 `text/event-stream` 后，错误不能再按 JSON `ApiResponse` 写回。能进入业务流的错误应发送 SSE `error` 事件；连接关闭或容器异常只能关闭响应。
- 普通浏览器刷新、切页或 SSE 连接断开不代表用户停止生成。后端仍会消费模型流到结束并在完成后保存完整 assistant 消息。
- 用户显式停止时，取消接口会中断模型订阅，并把已生成的 assistant 片段保存为 `STOPPED`。
- 模型正常完成且 assistant 内容非空时，`chat_messages.status` 保存为 `DONE`；模型截断、Provider 异常或后端调用异常且 assistant 内容非空时保存为 `ERROR`；模型还未返回任何 assistant 内容就失败时不保存 assistant 消息，只保留 user 消息。
- 前端遇到非用户主动停止的错误后，会先显示“未完成”气泡，再按本轮 `requestId` 延迟刷新会话详情；只有后端已经写入同一个 `requestId` 的 assistant 消息时，才用 SQLite 中的真实 `DONE/ERROR` 消息覆盖本地临时状态。

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
