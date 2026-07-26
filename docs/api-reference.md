# CogniNote API 参考

本文档记录当前后端对外暴露的 HTTP API。普通 JSON API 使用统一响应格式；RAG 对话流式接口使用 SSE，不做 JSON 包装。桌面模式下，`/api/**` 还会要求 Tauri 桌面壳注入的本机会话令牌。

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

## 桌面模式请求头

当后端由 Tauri 桌面壳启动时，会带有：

```text
COGNINOTE_DESKTOP=true
COGNINOTE_DESKTOP_SESSION_TOKEN=<random-token>
```

此时所有 `/api/**` 请求必须带 header：

```text
X-CogniNote-Desktop-Session: <random-token>
```

Tauri 健康检查、前端 JSON API 和聊天 SSE 请求会自动加该 header。普通 `mvn spring-boot:run`、Vite 浏览器开发模式或没有设置 `COGNINOTE_DESKTOP=true` 的后端进程不强制该 header。

缺失或错误 token 返回 `401`：

```json
{
  "success": false,
  "code": "UNAUTHORIZED",
  "message": "Desktop session token is missing or invalid",
  "data": null,
  "timestamp": 1780000000000
}
```

静态页面、SPA 路由和 `/assets/**` 不需要 token，只有 API 访问受保护。

## 系统状态

```text
GET /api/system/status
```

返回应用名、版本、运行状态和当前数据目录。

## 备份与恢复

```text
GET  /api/system/data-protection/status
POST /api/system/backups
POST /api/system/restores/preflight
POST /api/system/restores/{restoreId}/schedule
DELETE /api/system/restores/{restoreId}
GET  /api/system/restores/{restoreId}
```

`POST /api/system/backups` 生成受控临时备份并返回 `backupId`、建议文件名、大小、SHA-256、schema 版本和 `containsSecrets=true`。桌面壳再通过保存对话框复制该 ID 对应的文件，后端接口不接受任意目标路径。

`.cogninote-backup` 包含 SQLite 中的文档、chunks、聊天、模型配置、维护历史和知识图谱，也包含模型与联网搜索 API Key 明文。Lucene、日志、用户原始文件和 WebView 缓存不进入备份。

恢复文件先由桌面壳复制到受控 inbox，`preflight` 请求只提交随机 `importId`：

```json
{
  "importId": "01234567-89ab-cdef-0123-456789abcdef"
}
```

预检会验证 ZIP 条目、manifest、大小、SHA-256、schema 版本、SQLite 完整性、外键和关键业务引用。通过后返回 `restoreId`、数据量和 `PREFLIGHTED` 状态；调用 `schedule` 后写入跨进程 marker，桌面壳重启应用，后端在连接池打开前恢复数据库并重建 Lucene。用户取消确认时调用 `DELETE`，任务进入 `DISCARDED` 并立即删除含明文密钥的工作副本；超过 24 小时未确认的预检任务也会自动清理。

恢复状态可能为 `PREFLIGHTED`、`DISCARDED`、`SCHEDULED`、`SWAPPING`、`VALIDATING`、`REINDEXING`、`COMPLETED`、`ROLLED_BACK`、`REINDEX_FAILED`。无效包返回 `422 INVALID_BACKUP`，并发恢复返回 `409 CONFLICT`，空间不足返回 `507 INSUFFICIENT_STORAGE`。

## 文档

### 查询文档列表

```text
GET /api/documents
```

返回已导入文档列表，按更新时间倒序排列。文档 `status` 当前支持 `PARSED`、`SKIPPED`、`FAILED`、`OCR_REQUIRED`；`OCR_REQUIRED` 表示 PDF 没有可抽取文本层。用户配置并启用 OCR 后，可以同步或重新解析目录让这类 PDF 进入知识库。

每个 `DocumentSummaryResponse` 都可能携带 `lastFailure`。`FAILED` / `OCR_REQUIRED` 表示文档当前不可检索；`PARSED + lastFailure` 表示旧 chunks 和索引仍可使用，但最近一次同步、重新解析或索引操作失败。处理成功后，后端会自动清空 `lastFailure`。

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

导入成功后会写入 SQLite，并同步更新 Lucene 索引。导入失败的文件会返回结构化失败诊断，不删除用户原始文件。

导入、同步和重新解析响应中的 `failedCount` 表示本次处理失败的文件数。即使某个文件保留了旧的 `PARSED` 结果，本次处理失败仍会计数并进入 `failures[]`；健康接口的 `summary.failedCount` 才表示当前不能进入知识库检索的 `FAILED` 和 `OCR_REQUIRED` 文档数。

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

### 同步目录文件

```text
POST /api/knowledge-folders/{id}/sync
```

扫描已导入目录中的支持文件，只解析新增或修改的文件，并为缺失索引的旧文档补写 Lucene；未变化文件会跳过，不做整目录索引重建。若 PDF 没有可抽取文本层且 OCR 未启用，会记录为 `OCR_REQUIRED` 并计入 `failedCount`；若 OCR 已启用且配置可用，则按页识别。每页成功后立即保存本地检查点，后续页面失败时普通同步会从首个未完成页面继续；整份 PDF 完成前不会生成正式 chunks 或 Lucene 索引。若本地目录中某个旧文件已被删除，同步会删除应用内对应文档、检查点、chunks 和索引记录，但不触碰用户文件系统。同步目录必须处于启用状态。

响应体 `data` 为本次扫描统计：

```json
{
  "scannedCount": 3,
  "parsedCount": 1,
  "skippedCount": 2,
  "failedCount": 0,
  "failures": []
}
```

`failures[]` 使用统一的 `DocumentFailureResponse` 契约：

```json
{
  "sourcePath": "D:/notes/scanned.pdf",
  "stage": "MODEL_CALL",
  "code": "MODEL_AUTH_FAILED",
  "message": "视觉模型鉴权失败。",
  "detail": "DASHSCOPE / qwen3-vl-plus / HTTP 401 / invalid_api_key / 第 1 页",
  "suggestion": "检查视觉模型 API Key、Base URL 和账号权限。",
  "occurredAt": 1780000000000,
  "pageNumber": 1,
  "provider": "DASHSCOPE",
  "modelName": "qwen3-vl-plus",
  "httpStatus": 401,
  "providerErrorCode": "invalid_api_key",
  "completedPages": 7,
  "totalPages": 10,
  "resumePage": 8
}
```

`stage` 支持 `SCAN`、`READ`、`PARSE`、`OCR`、`MODEL_CONFIG`、`MODEL_CALL`、`CHUNK`、`PERSIST`、`INDEX`、`UNKNOWN`。`message` 是用户可读摘要，`detail` 是经过脱敏和长度限制的技术详情；接口不会返回 API Key、Authorization Header、图片 Base64、完整请求体、Java 堆栈或未经处理的 Provider 响应。`completedPages/totalPages/resumePage` 只在存在可恢复的 PDF OCR 检查点时返回，用于提示“已保存 7/10 页，下次从第 8 页继续”。旧运行记录中的两字段失败对象会按 `UNKNOWN / LEGACY_FAILURE` 返回，新字段为 `null`。

目录健康详情中的 `failedDocuments[]` 额外返回兼容字段 `lastFailure`。该字段复用上述结构化失败对象，使健康抽屉也能展示 OCR 已完成页数和下次续传页；普通文件缺失、文件变化等问题的 `lastFailure` 为 `null`。

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
      "stage": "PARSE",
      "code": "NO_USABLE_TEXT",
      "message": "文档没有可用于知识库的正文。",
      "detail": "Parsed document contains no usable text",
      "suggestion": "检查文档内容和格式后重新解析。",
      "occurredAt": 1780000000000,
      "pageNumber": null,
      "provider": null,
      "modelName": null,
      "httpStatus": null,
      "providerErrorCode": null,
      "completedPages": null,
      "totalPages": null,
      "resumePage": null
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

第 33 阶段后，前端删除目录优先调用维护队列接口 `POST /api/knowledge-maintenance/runs/folders/{id}/delete`。旧删除接口仍保留兼容。删除目录会清理该目录 scope 下的 `knowledge_folder_runs` 维护记录，避免删除后继续显示孤儿可信状态数据；`ALL` scope 和其他目录的运行记录不受影响。

## 知识库健康

知识库健康接口只做诊断，不会自动同步、重建、启停或删除。状态由 SQLite 文档记录、目录配置、索引字段、Lucene reader 统计和本地文件元数据即时派生；维护任务队列与运行历史保存在 `knowledge_folder_runs` 中。

### 查询全库健康

```text
GET /api/knowledge-health
```

返回全库状态、聚合问题和目录健康摘要：

```json
{
  "status": "WARNING",
  "summary": {
    "folderCount": 2,
    "enabledFolderCount": 1,
    "documentCount": 42,
    "parsedCount": 40,
    "failedCount": 1,
    "unindexedCount": 1,
    "missingLocalFileCount": 0,
    "staleLocalFileCount": 2,
    "newLocalFileCount": 1,
    "chunkCount": 560,
    "lastIngestedAt": 1780000000000,
    "lastIndexedAt": 1780000005000,
    "luceneDocumentCount": 40,
    "luceneChunkCount": 560,
    "embeddingConfigured": false,
    "indexConsistent": true,
    "runningRunCount": 1,
    "queuedRunCount": 2,
    "answerReady": false,
    "searchableDocumentCount": 39,
    "syncIssueCount": 3,
    "retrievalIssueCount": 3,
    "conflictIssueCount": 1,
    "graphStaleCount": 1
  },
  "issues": [],
  "folders": [],
  "currentRuns": [],
  "queuedRuns": [],
  "latestRun": null
}
```

`status` 支持 `HEALTHY`、`WARNING`、`ERROR`、`DISABLED`、`EMPTY`。`issues[].action` 是建议动作，例如 `SYNC_FOLDER`、`REPARSE_FOLDER`、`REPAIR_INDEX`、`REBUILD_INDEX`、`DELETE_FOLDER`，前端仍需调用对应维护接口执行。停用目录返回 `DISABLED`，作为用户主动排除检索范围的维护状态，不计入全库问题数量或 `WARNING/ERROR`。

第 32 阶段新增的 `summary.luceneDocumentCount` 和 `summary.luceneChunkCount` 来自 Lucene reader 实际统计；`summary.indexConsistent=false` 表示 SQLite 中应已索引的文档/chunk 与 Lucene reader 统计不一致；`summary.embeddingConfigured=false` 表示当前没有可用 Embedding，向量或混合检索需要配置向量模型。

第 34 阶段新增的 `summary.answerReady` 表示当前资料是否具备基础问答条件；`searchableDocumentCount` 表示已解析且已索引的文档数；`syncIssueCount` 汇总本地新增、变化和缺失；`retrievalIssueCount` 汇总解析失败、需 OCR、未索引、索引不一致和 Embedding 降级；`conflictIssueCount` 汇总重复内容和疑似版本冲突；`graphStaleCount` 表示已生成图谱落后于当前资料状态的 scope 数量。`summary.failedCount` 包含 `FAILED` 和 `OCR_REQUIRED` 文档。

当前问题代码：

| code | severity | 含义 | 建议动作 |
| --- | --- | --- | --- |
| `FOLDER_NOT_FOUND` | `ERROR` | 目录路径当前不可访问 | 删除目录记录或重新导入正确目录 |
| `NO_DOCUMENTS` | `WARNING` | 启用目录没有文档记录 | 同步目录或检查递归扫描 |
| `DOCUMENT_PROCESSING_FAILED` | `WARNING` | 文档最近在读取、解析、OCR、模型调用、持久化或索引阶段失败 | 按实际失败建议修复后同步或重新解析目录 |
| `PARSE_FAILED` | `WARNING` | 旧版本的通用解析失败问题码，前端继续兼容 | 修复源文件后同步目录 |
| `PDF_OCR_REQUIRED` | `WARNING` | PDF 没有可抽取文本层，需 OCR 后才能进入知识库 | 配置 OCR 后重新解析目录 |
| `UNINDEXED_DOCUMENTS` | `ERROR` | 已解析文档尚未进入 Lucene，常见原因是 Embedding 供应商限流后仍未写入索引 | 补写索引 |
| `STALE_LOCAL_FILES` | `WARNING` | 本地文件大小或修改时间已变化 | 同步目录 |
| `MISSING_LOCAL_FILES` | `WARNING` | 已记录文件在本地不存在 | 同步目录清理应用内记录 |
| `DISABLED_FOLDER` | `INFO` | 目录已停用；当前实现仅作为状态语义保留，不进入问题列表 | 在目录管理列表中启用目录 |
| `INDEX_INCONSISTENT` | `ERROR` | SQLite 事实与 Lucene reader 统计不一致 | 重建索引 |
| `EMBEDDING_UNCONFIGURED` | `WARNING` | 未配置可用 Embedding，向量或混合检索不可用 | 配置向量模型或切换关键词检索 |
| `GRAPH_STALE` | `INFO` | 已生成图谱早于最新资料同步或索引状态 | 重建图谱或进入知识图谱页查看 |
| `DUPLICATE_DOCUMENT_CONTENT` | `INFO` | 多个已解析资料内容完全相同，可能增加检索噪音 | 查看目录资料 |
| `POSSIBLE_VERSION_CONFLICT` | `WARNING` | 疑似同一资料存在多个不同内容版本 | 查看目录资料 |

### 查询目录健康详情

```text
GET /api/knowledge-health/folders/{id}
```

返回该目录的问题清单、失败/未索引/缺失/疑似变化文件和最近维护记录：

```json
{
  "folderId": "folder-xxx",
  "status": "WARNING",
  "issues": [
    {
      "code": "MISSING_LOCAL_FILES",
      "severity": "WARNING",
      "message": "有 1 个已记录文件在本地不存在。",
      "action": "SYNC_FOLDER",
      "scopeType": "KNOWLEDGE_FOLDER",
      "scopeId": "folder-xxx",
      "count": 1
    }
  ],
  "failedDocuments": [],
  "unindexedDocuments": [],
  "missingLocalFiles": [],
  "staleLocalFiles": [],
  "runs": []
}
```

健康诊断会比较本地文件是否存在、文件大小和修改时间，并轻量扫描目录中尚未入库的新文件数量；不重新计算内容 hash。真正的新增、修改、删除收敛仍由“同步目录”维护任务完成。单个文件访问异常会转成文件不可访问问题，不会让整个健康接口失败。

### 查询维护运行记录

```text
GET /api/knowledge-health/runs?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx&limit=20
```

`scopeType` 可省略；支持 `ALL`、`KNOWLEDGE_FOLDER`、`UNASSIGNED`。返回导入、同步、重新解析、补写索引、重建索引、启停和删除的最近记录。

运行记录 `operation` 支持 `IMPORT`、`SYNC`、`REPARSE`、`REPAIR_INDEX`、`REBUILD_INDEX`、`ENABLE`、`DISABLE`、`DELETE`；`status` 支持 `QUEUED`、`RUNNING`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`COMPLETED_WITH_WARNINGS`、`FAILED`。`phase`、`currentItem`、`queuedAt`、`startedAt`、`completedAt`、`durationMs` 和 `queuePosition` 用于前端展示当前队列和历史详情。

任务整体失败时，列表和详情响应通过 `errorMessage`、`errorStage`、`errorCode`、`errorDetail` 返回运行级诊断，例如目录不存在或整库索引初始化失败。单文件失败不会写入这些运行级字段，而是保存在详情响应的 `failures[]` 中。

分页查询维护记录：

```text
GET /api/knowledge-health/runs/page?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx&page=1&pageSize=10
```

返回：

```json
{
  "items": [],
  "total": 42,
  "page": 1,
  "pageSize": 10
}
```

旧 `/runs` 接口继续返回列表，维护记录弹窗优先使用 `/runs/page`，避免前端猜测是否还有下一页。

## 知识库维护任务队列

第 33 阶段后，前端所有知识库维护动作优先调用统一任务队列接口。旧目录接口和旧索引重建接口保留兼容，但不再作为前端状态事实源。

### 入队维护任务

```text
POST /api/knowledge-maintenance/runs/rebuild-index
POST /api/knowledge-maintenance/runs/repair-index
POST /api/knowledge-maintenance/runs/import-folder
POST /api/knowledge-maintenance/runs/folders/{id}/sync
POST /api/knowledge-maintenance/runs/folders/{id}/reparse
POST /api/knowledge-maintenance/runs/folders/{id}/rebuild
POST /api/knowledge-maintenance/runs/folders/{id}/repair-index
POST /api/knowledge-maintenance/runs/folders/{id}/enabled
POST /api/knowledge-maintenance/runs/folders/{id}/delete
```

导入目录请求体与旧导入接口一致：

```json
{
  "folderPath": "D:/notes",
  "recursive": true
}
```

启停目录请求体：

```json
{
  "enabled": false
}
```

入队成功返回 `KnowledgeFolderRunResponse`。如果同一 scope、同一 operation 已有 `QUEUED/RUNNING/CANCELLING` 任务，后端返回已有任务，避免重复入队。

`repair-index` 只处理 `status=PARSED AND indexed_at IS NULL` 的文档，SQLite chunks 是事实来源；它不扫描文件系统、不重新解析 PDF，也不重建已经索引成功的文档。该接口主要用于 Embedding 供应商限流后补写少量缺失 Lucene 条目。全库补写使用 `POST /api/knowledge-maintenance/runs/repair-index`，目录补写使用 `POST /api/knowledge-maintenance/runs/folders/{id}/repair-index`。

`reparse` 会重新读取并解析目录内当前支持文件，忽略未变化文件跳过逻辑，主要用于开启 OCR 后处理旧的 `OCR_REQUIRED` PDF。该操作会先清除未完成 PDF 的 OCR 页面检查点并从第一页重新识别；需要保留进度并从失败页继续时应使用普通 `sync`。它不修改用户原始文件；OCR 检查点、最终 chunks 和索引都只写入本地应用数据。

### 查询队列

```text
GET /api/knowledge-maintenance/runs/queue
```

返回：

```json
{
  "currentRuns": [],
  "queuedRuns": [],
  "latestRun": null
}
```

队列为单机 FIFO，同一时间最多一个 `RUNNING`。应用启动时会把遗留 `QUEUED` 标记为 `CANCELLED`，把遗留 `RUNNING/CANCELLING` 标记为 `FAILED`。

### 查询单个任务

```text
GET /api/knowledge-maintenance/runs/{runId}
```

返回维护运行详情，包括结构化单文件失败列表 `failures[]`。运行级 `errorStage/errorCode/errorDetail` 与单文件 `failures[]` 分开，避免把目录扫描失败误显示成某个文件解析失败。前端在 SSE 终态事件丢失时会用该接口兜底恢复完成提示。

### 订阅任务事件

```text
GET /api/knowledge-maintenance/runs/{runId}/events
```

该接口使用 `text/event-stream`。事件名包括：

| event | 含义 |
| --- | --- |
| `maintenance-run-snapshot` | 连接建立后的当前任务快照 |
| `maintenance-run-queued` | 任务已进入等待队列 |
| `maintenance-run-started` | 任务开始执行 |
| `maintenance-run-progress` | 任务阶段、当前项或统计变化 |
| `maintenance-run-cancelling` | 收到取消请求；当前只作为历史兼容事件 |
| `maintenance-run-cancelled` | 等待任务已取消 |
| `maintenance-run-completed` | 任务完成 |
| `maintenance-run-failed` | 任务失败 |
| `maintenance-queue-updated` | 队列顺序或当前任务发生变化 |

维护任务是服务端单向状态推送场景，因此使用项目已有 SSE 通道，不新增 WebSocket 技术栈。

### 取消任务

```text
POST /api/knowledge-maintenance/runs/{runId}/cancel
```

只允许取消 `QUEUED` 任务。`RUNNING` 任务已经开始修改 SQLite、Lucene 或派生数据，接口会返回业务错误：

```json
{
  "code": "KNOWLEDGE_MAINTENANCE_ERROR",
  "message": "只能取消等待中的维护任务；正在运行的任务会自动执行到安全完成点。"
}
```

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

第八阶段开始，对话模型和 Embedding 模型分开维护；第 36-4 阶段新增独立 VISION 模型配置供模型 OCR 使用。普通 CRUD 兼容接口使用 `/api/model-configs`；设置页使用 `/api/model-configs/settings...` 快照接口，避免前端自己拼装 active、列表和右侧表单。旧 `/api/model-config` 仅作为过渡兼容接口保留。

### 查询配置列表

```text
GET /api/model-configs?role=CHAT
GET /api/model-configs?role=EMBEDDING
GET /api/model-configs?role=VISION
```

返回指定类型的配置列表，active 配置排在前面。

### 查询 active 配置

```text
GET /api/model-configs/active
```

返回当前 active Chat、active Embedding 和 active Vision：

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
    "embeddingRequestsPerMinute": 300,
    "embeddingTokensPerMinute": 300000,
    "embeddingBatchSize": 16,
    "active": true
  },
  "vision": {
    "id": "active-vision",
    "role": "VISION",
    "provider": "DASHSCOPE",
    "displayName": "DashScope Vision",
    "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
    "apiKeyConfigured": false,
    "apiKey": "",
    "modelName": "qwen3-vl-plus",
    "temperature": 0.0,
    "active": true
  }
}
```

### 设置页快照

```text
GET /api/model-configs/settings?role=CHAT
GET /api/model-configs/settings?role=EMBEDDING
GET /api/model-configs/settings?role=VISION
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
      "embeddingRequestsPerMinute": 300,
      "embeddingTokensPerMinute": 300000,
      "embeddingBatchSize": 16,
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
  "embeddingDimensions": 1024,
  "embeddingRequestsPerMinute": 300,
  "embeddingTokensPerMinute": 300000,
  "embeddingBatchSize": 16
}
```

Embedding 限速字段说明：

| 字段 | 含义 | 默认值 | 校验范围 |
| --- | --- | ---: | --- |
| `embeddingRequestsPerMinute` | 每分钟 Embedding 请求数，后端换算为最小请求间隔 | `300` | `1` 到 `10000` |
| `embeddingTokensPerMinute` | 每分钟输入 token 估算上限，按 60 秒滚动窗口控制 | `300000` | `1000` 到 `10000000` |
| `embeddingBatchSize` | 每批向量化的 chunk 数 | `16` | `1` 到 `128` |

供应商返回 429、`rate limit`、`TPM limit` 或 `RPM limit` 时，维护任务会提示“供应商限流，已等待后重试；这不是文档解析失败。”并最多自动重试 5 次。连续限流后仍失败的文档保持 `indexed_at=NULL`，后续可通过补写索引接口继续处理。

### 更新配置

```text
PUT /api/model-configs/{id}
```

请求体同新建配置。保存时 API Key 留空表示复用该配置已保存 key，避免用户每次修改模型参数都重新输入密钥。设置页更新接口可额外传 `clearApiKey=true` 显式清空该配置的本机密钥；旧兼容接口不支持该字段。

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

模型设置页在用户点开“模型 ID”下拉框时，前端会自动调用该接口并把候选模型展示出来。模型 ID 输入框仍允许用户手动创建 provider 支持但列表中没有返回的模型。

响应示例：

```json
{
  "models": [
    {
      "id": "qwen-plus",
      "name": "qwen-plus",
      "capability": "UNKNOWN"
    },
    {
      "id": "text-embedding-v4",
      "name": "text-embedding-v4",
      "capability": "EMBEDDING"
    }
  ],
  "fetchedAt": 1760000000000
}
```

- `models[].capability`：后端对 provider `/models` 响应做的保守能力标注，取值为 `CHAT`、`EMBEDDING` 或 `UNKNOWN`。
- `UNKNOWN`：provider 没有返回足够能力信息，不代表模型不可用；前端仍会展示该模型并允许用户选择。
- `fetchedAt`：后端拉取模型列表的时间戳，前端用于判断短时间缓存是否仍可复用。

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

第十三阶段开始，左侧会话列表和历史消息都由后端 SQLite 提供。前端刷新后通过会话 API 恢复消息、RAG 引用来源、用户引用的助手回复片段、检索模式和 stopped/error 状态。

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

返回会话详情、完整消息列表和 `contextUsage`。assistant 消息会带回 `sources`，用于刷新页面后恢复 RAG 引用来源；user 消息可能带回 `references`，用于恢复用户发送时引用的助手回复片段；`contextUsage` 会按当前 active Chat 配置重新估算上下文窗口、摘要和最近原文消息占用。

消息中的 `references` 结构：

```json
[
  {
    "id": "ref-1",
    "messageId": "assistant-message-id",
    "snippet": "用户选中的助手回复片段"
  }
]
```

`references` 为空或旧数据没有 `references_json` 时返回空数组。`sources` 表示 assistant 回答产生时的知识库来源快照；`references` 表示 user 消息发送时引用的助手回复片段，两者语义不同。

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

## 联网搜索设置

第 35 阶段新增联网搜索设置 API。设置保存在 SQLite `app_settings` 中，MVP 只支持 `EXA` provider；API Key 只允许通过保存接口写入，所有响应都只返回 `apiKeyConfigured`，永不回显明文。

全局设置只表达“联网能力是否可用”。聊天时还需要本轮请求传入 `useWebSearch=true`，后端才会把 `WebSearchTools.searchWeb` 挂载给模型。

### 查询联网搜索设置

```text
GET /api/web-search/settings
```

响应 `data`：

```json
{
  "enabled": false,
  "provider": "EXA",
  "apiKeyConfigured": false,
  "maxResults": 5,
  "maxCallsPerTurn": 2,
  "timeoutMs": 10000,
  "searchMode": "auto"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `enabled` | 全局联网搜索是否实际启用。没有已保存或本次提交的 API Key 时，后端会归一化为 `false`。 |
| `provider` | 当前固定为 `EXA`，保留字段用于后续 provider 扩展。 |
| `apiKeyConfigured` | 服务端是否已有 Exa API Key。 |
| `maxResults` | 单次搜索返回结果数，范围 `1` 到 `10`。 |
| `maxCallsPerTurn` | 单轮聊天最多允许模型调用搜索工具的次数，范围 `1` 到 `3`。 |
| `timeoutMs` | Exa 请求超时，范围 `1000` 到 `30000` 毫秒。 |
| `searchMode` | Exa 搜索模式，`auto` 或 `fast`。 |

### 保存联网搜索设置

```text
PUT /api/web-search/settings
```

请求体：

```json
{
  "enabled": true,
  "provider": "EXA",
  "apiKey": "exa-...",
  "maxResults": 5,
  "maxCallsPerTurn": 2,
  "timeoutMs": 10000,
  "searchMode": "auto"
}
```

`apiKey` 传空或省略表示沿用服务端已保存的旧 Key；传入新 Key 时覆盖旧 Key。响应字段与查询接口一致，不返回明文 `apiKey`。如果 `enabled=true` 但既没有旧 Key 也没有本次提交的新 Key，后端会保存为未启用状态。

### 测试联网搜索

```text
POST /api/web-search/test
```

该接口使用固定测试 query 调用当前 provider，只验证配置和网络可用性，不写入聊天记录，也不返回完整网页内容。

响应 `data`：

```json
{
  "success": true,
  "message": "OK",
  "resultCount": 5
}
```

## OCR 设置

第 36-4 阶段新增 OCR 设置 API。设置保存在 SQLite `app_settings` 的 `ocr.settings` JSON 快照中，第一版引擎固定为 `MODEL_VISION`。OCR 默认关闭；启用后，无文本层 PDF 会按页渲染为图片并上传到当前 VISION 模型服务商，可能产生模型 token 或图片费用。

OCR 设置只保存开关和运行限制；模型 API Key 归属独立的 VISION 模型配置，并继续按本机模型配置逻辑明文可见、可复制和可清空。日志、异常消息、OCR 测试响应和维护运行记录不得输出密钥。

### 查询 OCR 设置

```text
GET /api/ocr/settings
```

响应 `data`：

```json
{
  "enabled": false,
  "engine": "MODEL_VISION",
  "available": false,
  "visionModel": {
    "id": "active-vision",
    "provider": "DASHSCOPE",
    "displayName": "DashScope Vision",
    "baseUrl": "https://dashscope.aliyuncs.com/api/v1",
    "apiKeyConfigured": false,
    "modelName": "qwen3-vl-plus"
  },
  "limits": {
    "maxPagesPerDocument": 200,
    "timeoutPerPageSeconds": 20,
    "monthlyCallBudget": 1000
  }
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `enabled` | OCR 开关状态。即使开关为 `true`，VISION 模型未配置 API Key 时仍不可用。 |
| `engine` | 当前固定为 `MODEL_VISION`，旧 `BAIDU_OCR` 配置读取时会迁移为该值并关闭。 |
| `available` | 当前设置是否具备运行 OCR 的必要条件，即 `enabled=true` 且 VISION 模型 API Key 已配置。 |
| `visionModel` | 当前 active VISION 模型摘要；不返回 API Key 明文。 |
| `limits.maxPagesPerDocument` | 单个 PDF 最多 OCR 页数，范围 `1` 到 `500`。 |
| `limits.timeoutPerPageSeconds` | 单页 OCR 请求超时，范围 `3` 到 `600` 秒，默认 `20` 秒；`600` 秒是允许上限而非默认值。超时会中止当前页模型 OCR 并让本次解析失败。 |
| `limits.monthlyCallBudget` | 本地提示用月调用预算，范围 `1` 到 `1000000`，不等同于模型服务商账单。 |

### 保存 OCR 设置

```text
PUT /api/ocr/settings
```

请求体：

```json
{
  "enabled": true,
  "engine": "MODEL_VISION",
  "limits": {
    "maxPagesPerDocument": 200,
    "timeoutPerPageSeconds": 20,
    "monthlyCallBudget": 1000
  }
}
```

响应字段与查询接口一致。VISION 模型的 API Key 通过 `/api/model-configs/settings/configs` 保存，不在 OCR 设置接口中传递。

### 测试 OCR 连接

```text
POST /api/ocr/test
```

该接口使用后端生成的小测试图片调用当前 VISION 模型，并要求返回固定测试文字，用于验证图片输入确实可用；不会主动识别用户文件，响应不得包含 API Key。

响应 `data`：

```json
{
  "success": true,
  "message": "视觉模型图片输入测试成功。",
  "engine": "MODEL_VISION",
  "modelName": "qwen3-vl-plus"
}
```

## 知识图谱

知识图谱是知识库资料的派生物。后端基于已解析 chunks 调用 active Chat 模型抽取实体、中文关系谓词、关系描述和证据，写入 SQLite 图谱缓存，再生成思维导图和关系图视图。导入文档或重建 Lucene 索引不会自动重建图谱，必须由用户显式触发。

`scopeType` 支持：

| 值 | scopeId |
| --- | --- |
| `ALL` | 可省略，表示全库 |
| `KNOWLEDGE_FOLDER` | 必填，知识库目录 ID |
| `DOCUMENT` | 必填，文档 ID |

`viewType` 支持 `MINDMAP` 和 `GRAPH`。

### 查询已生成图谱清单

```text
GET /api/knowledge-graphs
```

返回已生成过前端视图的 scope 摘要列表，不返回 `MINDMAP` / `GRAPH` payload。客户端进入知识图谱页时应先调用该接口展示“已有知识图谱”入口，并在前端完成搜索、范围类型筛选和视图可用性筛选；用户点击具体条目的“查看”后，再调用 `status` 和 `view` 读取完整图谱并在弹窗中渲染。

```json
[
  {
    "scopeType": "KNOWLEDGE_FOLDER",
    "scopeId": "folder-xxx",
    "scopeName": "项目资料",
    "scopeSubtitle": "D:/notes/project",
    "nodeCount": 42,
    "edgeCount": 31,
    "mindmapReady": true,
    "graphReady": true,
    "generatedAt": 1780000005000,
    "latestRun": {
      "runId": "run-xxx",
      "scopeType": "KNOWLEDGE_FOLDER",
      "scopeId": "folder-xxx",
      "status": "COMPLETED",
      "modelConfigId": "chat-config-xxx",
      "promptVersion": "kg-extract-v2",
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
    }
  }
]
```

同一 scope 同时存在 `MINDMAP` 和 `GRAPH` 时只返回一条摘要，`generatedAt` 取对应 scope 最新视图更新时间。目录或文档已被删除时接口仍会返回摘要，`scopeName` 分别兜底为 `已删除目录` / `已删除文档`，`scopeSubtitle` 返回原始 scopeId，避免旧图谱入口导致页面失败。

### 删除已生成图谱

```text
DELETE /api/knowledge-graphs?scopeType=KNOWLEDGE_FOLDER&scopeId=folder-xxx
```

删除指定 scope 已生成的知识图谱数据，包括节点、关系、证据、前端视图和运行历史。该操作不会删除用户原始目录、文档、chunks 或 chunk 抽取缓存；保留 chunk 抽取缓存是为了后续重新生成同一 scope 时继续复用模型抽取结果。目录或文档已经被删除时，只要清单中仍存在旧 scope 视图，客户端仍可用原始 `scopeId` 调用该接口清理旧图谱缓存。

同一 scope 正在 `QUEUED` / `RUNNING` 时会返回 400，客户端应提示用户先取消或等待完成。

成功响应：

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": true,
  "timestamp": 1780000000000
}
```

运行中删除的错误响应示例：

```json
{
  "success": false,
  "code": "BAD_REQUEST",
  "message": "当前范围的知识图谱正在生成，请取消或等待完成后再删除。",
  "data": null,
  "timestamp": 1780000000000
}
```

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
  "promptVersion": "kg-extract-v2",
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
    "promptVersion": "kg-extract-v2",
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
      "FUNCTIONAL": 1
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
        "label": "FUNCTIONAL",
        "displayLabel": "使用",
        "description": "CogniNote 使用 Lucene 做混合检索",
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

`nodes` / `edges` 的基础字段保持兼容。`edges[].label` 保持后端归一化后的内部粗分类 code，用于筛选、统计和兼容旧缓存，不直接作为边标签展示；允许值为 `RELATED`、`STRUCTURAL`、`FUNCTIONAL`、`CAUSAL`、`SEQUENCE`、`OWNERSHIP`、`COMPARISON`、`CONSTRAINT`。`edges[].displayLabel` 是唯一用于 UI 短标签展示的中文关系谓词，来自模型输出并经后端校验，缺失或非法时兜底为 `相关`。`edges[].description` 是中文完整关系说明，用于 Inspector、邻接列表和证据抽屉展示完整语义。旧 `GRAPH` 缓存缺少 `displayLabel` 或 `description` 时，读取视图会按 edge id 从当前边事实表补齐；旧细粒度英文 `label` 会归一为粗分类，无法映射时统一视为 `RELATED`，避免前端继续展示英文关系码。`nodeTypeCounts`、`relationTypeCounts`、`hiddenNodeCount`、`sourceLabel` 和 `targetLabel` 是第 26 阶段新增的展示辅助字段，用于图例、筛选、Inspector 和列表视图。

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
  "useWebSearch": false,
  "topK": 8,
  "mode": "HYBRID",
  "references": [
    {
      "id": "ref-1",
      "messageId": "assistant-message-id",
      "snippet": "用户选中的助手回复片段"
    }
  ]
}
```

`requestId`、`conversationId`、`topK`、`mode`、`useKnowledgeBase`、`useWebSearch` 和 `references` 可省略。默认使用后端生成的 `requestId/conversationId`、active Chat 配置中的 `defaultTopK`、`HYBRID`、`useKnowledgeBase=true`、`useWebSearch=false` 和空引用列表。前端需要支持停止生成时，应在请求体中传入稳定 `requestId`，然后调用取消接口。

`references` 用于携带用户本轮引用的助手回复片段。v1 只支持引用助手消息文本；服务端会再次清洗，最多保留 5 个片段，单个片段最多 1200 字符，总片段最多 4000 字符，并按 `messageId + snippet` 去重。数据库中的 `chat_messages.content` 仍保存用户原始 `question`，引用片段写入 `chat_messages.references_json`，只在模型输入、会话记忆和 token 估算时拼接进上下文。

SSE 事件格式：

```text
event: meta
data: {"requestId":"...","conversationId":"...","retrievalMode":"HYBRID","sources":[...],"contextUsage":{"contextWindowTokens":128000,"usedTokens":680,"availableTokens":127320,"usageRatio":0.0053,"compressed":false,"summaryTokens":0,"recentMessageTokens":680,"recentMessageCount":2,"totalMessageCount":2,"summaryMessageSequence":0,"estimationMethod":"jtokkit:o200k_base"}}

event: delta
data: {"text":"..."}

event: tool
data: {"requestId":"...","toolName":"searchWeb","query":"Spring AI Tool Calling","status":"COMPLETED","durationMs":820,"message":"OK","sources":[{"index":1,"chunkId":"web:...","sourceType":"WEB","title":"Spring AI Reference","fileName":"Spring AI Reference","url":"https://docs.spring.io/spring-ai/reference/...","sourcePath":"https://docs.spring.io/spring-ai/reference/...","provider":"EXA","preview":"...","score":0.89,"publishedAt":"2026-06-01"}]}

event: done
data: {"usage":null,"contextUsage":{"contextWindowTokens":128000,"usedTokens":1240,"availableTokens":126760,"usageRatio":0.0097,"compressed":false,"summaryTokens":0,"recentMessageTokens":1240,"recentMessageCount":4,"totalMessageCount":4,"summaryMessageSequence":0,"estimationMethod":"jtokkit:o200k_base"}}

event: error
data: {"message":"..."}
```

事件顺序通常为：

```text
meta -> delta/tool -> done
```

有联网工具调用时，`tool` 事件可能穿插在 `delta` 前后；客户端应按 `requestId` 合并到当前 assistant 消息的 sources，不要等 `done` 才展示网页来源。异常时输出 `error`，事件顺序为 `meta -> delta/tool -> error`。客户端只有收到 `done` 或 `error` 终止事件时，才能认为本轮 SSE 流有明确结论；如果连接关闭但没有终止事件，应按回答未完成处理。如果 `HYBRID` 或 `VECTOR` 因 Embedding 不可用失败，RAG 服务会自动降级到 `KEYWORD`，并在 `meta.retrievalMode` 中返回实际检索模式。`useKnowledgeBase=false` 时路由到 `GENERAL_CHAT` 普通对话 Agent，不挂 RAG Advisor，`retrievalMode` 为 `null`、`sources` 为空，只注入模式隔离后的会话记忆。`useKnowledgeBase=true` 或省略时路由到 `KNOWLEDGE_BASE` 知识库 Agent；知识库模式会按 `queryContextualizerMode` 决定是否内部调用追问补全 Agent，必要时把历史主题补进检索 query，但请求体、SSE `meta` 和用户消息内容都不变。

`useWebSearch=false` 或省略时，后端不会读取联网搜索设置、不会挂载工具、不会调用 Exa，也不会产生 `tool` 事件。`useWebSearch=true` 时，后端还会检查全局联网设置是否启用、provider 是否为 `EXA`、API Key 是否已配置；只有全部满足才会把 `WebSearchTools.searchWeb` 加入本轮 ChatClient 工具列表。纯模型对话和知识库对话都可以开启联网：前者是会话记忆 + WebSearchTools，后者是会话记忆 + RAG Advisor + WebSearchTools。

重要约束：

- SQLite 会保存全量会话历史。模型输入由“会话摘要 + token 预算内最近原文消息”组成；历史预算优先来自 active Chat 配置的 `contextWindowTokens`，默认 `128000`，并最多使用约 80% 窗口；默认至少保留最近 8 条原文消息，但不会把固定条数作为唯一记忆策略。
- 用户消息如果携带 `references`，模型实际看到的 user 内容会变成“引用片段块 + 用户问题”；SQLite 中的用户 `content` 不变。刷新会话后，`references_json` 会重新参与会话记忆和 token 估算，保证后续追问仍能继承当轮引用上下文。
- 同一会话可以在普通对话和知识库模式之间切换。后端会用 `agent_type` 标记消息，并在模型输入里隔离跨 Agent 历史：上一种 Agent 的拒答规则、引用规则和系统规则不能覆盖当前 Agent。
- 知识库模式下，后端可能按 `AUTO/ALWAYS/OFF` 策略使用 active Chat 模型对省略式追问生成内部 `retrievalQuery`。`AUTO` 本地打分器会综合短句、指代、省略补全、动作型请求、英文领域切换和完整问题反向信号；如果本地判断已触发但补全模型误判不改写，短动作型追问会用最近明确主题的用户问题构造本地兜底 query。这个 query 只用于检索，不写入 `chat_messages.content`，也不会通过 SSE 暴露；补全失败、非法 JSON 或 query 过长时会回退原问题检索。
- RAG 不再手动把 `{context}` 拼进 user prompt。知识库片段通过 Spring AI `RetrievalAugmentationAdvisor` 和 `CogninoteDocumentRetriever` 注入。
- Spring AI `Document.metadata` 不允许出现 `null`。后端转换 RAG sources 时会省略缺失的 `heading/pageNumber` 等可选字段，前端仍以 SSE `meta.sources` 作为引用来源展示事实来源。
- `sources[].sourceType` 可能是 `LOCAL` 或 `WEB`。旧消息缺少 `sourceType` 时按 `LOCAL` 兼容；网页来源会同时填充 `title/url/provider/preview`，并把 `fileName/sourcePath` 填为标题和 URL，保证旧展示路径也能显示基本信息。
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
