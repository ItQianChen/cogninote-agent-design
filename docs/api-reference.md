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
- `DELETE /api/documents/{id}` 删除成功时返回 `204 No Content`。

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

### 删除文档

```text
DELETE /api/documents/{id}
```

删除 SQLite 中的文档记录和 chunks，并清理 Lucene 中对应索引。不会删除用户原始文件。

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

从 SQLite 中已解析的 chunks 全量重建 Lucene 索引。Lucene 是可重建索引，不是业务事实来源。

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

第八阶段开始，对话模型和 Embedding 模型分开维护。普通前端使用 `/api/model-configs` 新接口；旧 `/api/model-config` 仅作为过渡兼容接口保留。

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

不能删除某个类型的唯一 active 配置。删除 active 配置时，后端会要求同类型仍有可用配置。

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

## RAG 流式对话

```text
POST /api/chat/stream
```

请求体：

```json
{
  "question": "这个项目如何打包？",
  "topK": 8,
  "mode": "HYBRID"
}
```

`topK` 和 `mode` 可省略。默认使用 active Chat 配置中的 `defaultTopK` 与 `HYBRID`。

SSE 事件格式：

```text
event: meta
data: {"conversationId":"...","retrievalMode":"HYBRID","sources":[...]}

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

异常时输出 `error`。如果 `HYBRID` 或 `VECTOR` 因 Embedding 不可用失败，RAG 服务会自动降级到 `KEYWORD`，并在 `meta.retrievalMode` 中返回实际检索模式。
