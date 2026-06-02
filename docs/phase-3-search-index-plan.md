# CogniNote Agent 第三阶段任务计划：Lucene 检索闭环

## Summary

第三阶段只做 Milestone 3：Lucene 检索，目标是在第二阶段 SQLite 文档摄入结果之上，建立本地 Lucene 索引，并提供可用的关键词检索、向量检索、混合检索和增量索引能力。

本阶段不做 RAG 问答、不做 LLM Gateway、不做 SSE 流式输出、不做引用答案生成，这些放到第四阶段。第三阶段交付的核心是：用户导入文档后，可以重建索引、查看索引状态、输入查询并看到命中的 chunk 与来源。

模型层采用 Spring AI 作为通用抽象，默认通过 Spring AI Alibaba DashScope 提供 Embedding；Lucene 和 SQLite 仍由 CogniNote 自己掌控，避免把本地混合检索核心变成普通向量库 Demo。

## Key Changes

- 后端依赖新增：
  - `org.apache.lucene:lucene-core:10.4.0`
  - `org.apache.lucene:lucene-analysis-common:10.4.0`
  - `org.apache.lucene:lucene-queryparser:10.4.0`
  - `org.springframework.ai:spring-ai-bom:1.1.2`
  - `com.alibaba.cloud.ai:spring-ai-alibaba-bom:1.1.2.3`
  - `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`
- Lucene 索引目录继续使用 Phase 1 已创建的 `%APPDATA%/CogniNote/index/lucene/`，由 `AppStorage.luceneIndexDir()` 提供路径。
- SQLite 仍是业务事实来源；Lucene 是可重建搜索索引，不保存完整展示文本。
- 新增检索 API：
  ```text
  GET  /api/index/status
  POST /api/index/rebuild
  POST /api/search
  ```
- 文档导入成功或删除文档后，同步更新 Lucene 索引；`POST /api/index/rebuild` 用 SQLite 中的 `chunks` 全量重建索引。
- 前端知识库页增加索引状态、重建按钮和最小搜索面板，搜索结果展示文件名、页码/标题、片段预览和分数。

## Scope Boundary

本阶段做：

- BM25 关键词索引和检索。
- Spring AI `EmbeddingModel` 网关，默认接 Spring AI Alibaba DashScope。
- Lucene 向量字段索引和 KNN 检索。
- BM25 + Vector 的混合结果融合。
- SQLite `documents.indexed_at` 写入和索引状态展示。
- 导入、删除、重建三个路径上的索引一致性。

本阶段不做：

- 对话模型配置页。
- `/api/chat`。
- RAG Prompt 组装。
- SSE 流式返回。
- 答案引用来源生成。
- 重排模型。
- OCR、HTML、`.doc`、Obsidian 双链解析。
- 异步任务队列和后台索引进度推送。

## Implementation Changes

### 1. 依赖与配置

- `pom.xml` 新增 Lucene 依赖，版本统一用 `lucene.version=10.4.0`。
- 引入 Spring AI BOM `1.1.2` 与 Spring AI Alibaba BOM `1.1.2.3`。
- 引入 `spring-ai-alibaba-starter-dashscope` 作为默认 Embedding provider。
- `application.yaml` 增加检索配置：
  ```yaml
  spring:
    ai:
    model:
      chat: none
      image: none
      video: none
      rerank: none
      audio:
        speech: none
        transcription: none
      embedding: ${COGNINOTE_AI_EMBEDDING_PROVIDER:none}
      embedding.text: ${COGNINOTE_AI_EMBEDDING_PROVIDER:none}
      embedding.multimodal: none
    dashscope:
      api-key: ${COGNINOTE_DASHSCOPE_API_KEY:}
      agent:
        enabled: false
      chat:
        enabled: false
      image:
        enabled: false
      embedding:
        options:
          model: ${COGNINOTE_EMBEDDING_MODEL:text-embedding-v4}

  app:
    search:
      top-k: ${COGNINOTE_SEARCH_TOP_K:8}
      bm25-weight: ${COGNINOTE_BM25_WEIGHT:0.6}
      vector-weight: ${COGNINOTE_VECTOR_WEIGHT:0.4}
    embedding:
      dimensions: ${COGNINOTE_EMBEDDING_DIMENSIONS:1024}
      batch-size: ${COGNINOTE_EMBEDDING_BATCH_SIZE:32}
  ```
- `spring.ai.model.embedding` 默认是 `none`，保证未配置 DashScope API Key 时应用仍能启动，关键词检索仍可用。
- Spring AI Alibaba 1.1.2.3 的文本 embedding 自动配置条件读取 `spring.ai.model.embedding.text`，并且默认会启用多个 DashScope 子模块；因此配置中需要同时设置 `embedding.text=none`，并关闭 Agent/Chat/Image 等第三阶段不用的模块。
- 启用 DashScope Embedding 时设置：
  ```powershell
  $env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
  $env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
  ```
- Embedding 配置先放在后端配置里，不做前端模型配置页；第四阶段再落 SQLite 模型配置。

### 2. SQLite 查询补强

- `DocumentRepository` 增加 chunk 回读能力：
  - 按 document id 查询 chunks。
  - 按 chunk id 批量查询 chunk + document 元数据。
  - 查询所有 `PARSED` 文档及其 chunks，用于全量重建。
  - 批量更新 `documents.indexed_at`。
- 删除文档时继续删除 SQLite 记录，同时调用 Lucene 删除对应 document id。
- 导入文件变化时，SQLite 成功写入后再替换 Lucene 中该 document id 的索引。

### 3. KnowledgeStore 抽象

新增 `search` 包，建立清晰边界：

```java
public interface KnowledgeStore {
    void indexDocument(IndexedDocument document);
    void deleteByDocumentId(String documentId);
    SearchResponse search(SearchRequest request);
    IndexStatusResponse status();
    void rebuildAll();
}
```

建议类型：

- `IndexedDocument`：document 元数据 + chunks。
- `IndexedChunk`：chunk id、document id、chunk index、content、content hash、heading、page number。
- `SearchRequest`：query、mode、topK。
- `SearchMode`：`KEYWORD`、`VECTOR`、`HYBRID`。
- `SearchHitResponse`：chunk id、document id、file name、source path、heading、page number、preview、score、keywordScore、vectorScore。
- `IndexStatusResponse`：indexed document count、indexed chunk count、last indexed time、index path、embedding configured。

### 4. Lucene BM25 索引

- 每个 SQLite chunk 对应一个 Lucene Document。
- Lucene 字段设计：
  - `chunk_id`：`StringField`，stored。
  - `document_id`：`StringField`，stored。
  - `file_name`：`StoredField` + 可选 `TextField`。
  - `source_path`：`StoredField`。
  - `chunk_index`：`StoredField` / numeric doc value。
  - `heading`：`TextField` + stored。
  - `page_number`：`StoredField`。
  - `content_hash`：`StringField`，stored。
  - `content_for_bm25`：`TextField`，不作为展示唯一来源。
  - `preview_text`：`StoredField`，只保存短预览。
- Analyzer 第一版用 `StandardAnalyzer`。中文检索先接受基础 tokenization 效果，不引入 IK/Jieba，避免依赖和词典复杂度提前爆炸。
- 关键词查询用 `MultiFieldQueryParser`，字段包含 `content_for_bm25`、`heading`、`file_name`。

### 5. Embedding Gateway

实现 CogniNote 自己的 `EmbeddingGateway`，内部依赖 Spring AI `EmbeddingModel`：

```java
public interface EmbeddingGateway {
    boolean isAvailable();
    List<float[]> embedBatch(List<String> texts);
    int dimensions();
}
```

- 默认实现使用 Spring AI Alibaba DashScope 自动配置出的 `EmbeddingModel`。
- 支持 batch embedding，默认按 32 个 chunk 一批，由 `app.embedding.batch-size` 控制。
- 返回向量维度必须等于配置的 `dimensions`，否则导入失败并给出明确错误。
- Embedding 未配置或连接失败时：
  - `KEYWORD` 搜索仍可用。
  - `VECTOR` / `HYBRID` 返回 400 或明确提示 embedding unavailable。
- 测试中使用 fake `EmbeddingGateway`，不依赖真实 DashScope API Key。

### 6. Lucene 向量索引

- 使用 Lucene `KnnFloatVectorField` 保存 chunk embedding。
- 向量字段名固定为 `embedding_vector`。
- 向量相似度使用 cosine 或 Lucene 默认支持的相似度配置，按依赖 API 最简单稳定的方式实现。
- 索引时只对 `PARSED` 文档的 chunks 生成 embedding。
- 同一 document id 更新时，先删除旧 Lucene 文档，再添加新 chunks，避免旧 chunk 残留。

### 7. 混合检索

搜索模式：

- `KEYWORD`：只跑 BM25。
- `VECTOR`：只跑 KNN。
- `HYBRID`：BM25 与 KNN 各取 `max(topK * 3, 20)`，归一化后融合。

融合策略：

- 对 BM25 和 Vector 分别做 min-max 归一化。
- 同一 chunk id 命中多路结果时合并：
  ```text
  finalScore = bm25Weight * normalizedKeywordScore + vectorWeight * normalizedVectorScore
  ```
- 只返回最终 Top K。
- 权重使用配置默认值 `0.6 / 0.4`，前端第三阶段可暂不开放调整。

### 8. 增量索引和一致性

- 导入成功：
  - SQLite 写入成功后调用 `KnowledgeStore.indexDocument(document)`。
  - 成功后写入 `documents.indexed_at = now`。
- 导入失败：
  - 删除该 document id 的 Lucene 索引。
  - `indexed_at` 保持 `NULL`。
- 删除文档：
  - 删除 SQLite 记录后删除 Lucene 中的 document id。
- 全量重建：
  - 清空 Lucene 索引。
  - 从 SQLite 读取所有 `PARSED` 文档和 chunks。
  - 重建 Lucene。
  - 更新对应 `indexed_at`。
- 启动时不自动全量重建，避免大知识库启动慢；只在状态接口提示未索引或索引为空。

### 9. API 行为

`GET /api/index/status` 返回：

```json
{
  "indexPath": "...",
  "indexedDocumentCount": 12,
  "indexedChunkCount": 180,
  "parsedDocumentCount": 12,
  "unindexedDocumentCount": 0,
  "lastIndexedAt": 1760000000000,
  "embeddingConfigured": true
}
```

`POST /api/index/rebuild` 返回：

```json
{
  "indexedDocumentCount": 12,
  "indexedChunkCount": 180,
  "failedDocumentCount": 0,
  "durationMs": 1234
}
```

`POST /api/search` 请求：

```json
{
  "query": "本地知识库如何打包",
  "mode": "HYBRID",
  "topK": 8
}
```

响应：

```json
{
  "query": "本地知识库如何打包",
  "mode": "HYBRID",
  "hits": [
    {
      "chunkId": "...",
      "documentId": "...",
      "fileName": "note.md",
      "sourcePath": "D:/notes/note.md",
      "heading": "打包方案",
      "pageNumber": null,
      "preview": "...",
      "score": 0.93,
      "keywordScore": 0.88,
      "vectorScore": 0.71
    }
  ]
}
```

### 10. 前端改造

- 知识库页新增索引状态条：
  - 已索引文档数。
  - 未索引文档数。
  - chunk 数。
  - 最后索引时间。
  - Embedding 是否可用。
- 新增“重建索引”按钮，调用 `POST /api/index/rebuild`。
- 新增搜索面板：
  - 输入 query。
  - mode 分段控制：关键词、向量、混合。
  - topK 输入或 select。
  - 结果列表展示文件名、路径、标题/页码、预览和分数。
- 前端继续保持单文件 `App.vue`，不引入组件库；等页面复杂度真正上来后再拆组件。

## Implementation Steps

1. 在 `pom.xml` 增加 Lucene、Spring AI、Spring AI Alibaba DashScope 依赖。
2. 在 `application.yaml` 增加 `spring.ai`、`app.search` 与 `app.embedding` 配置，并新增对应 properties records。
3. 扩展 `DocumentRepository`，提供 chunk 回读、全量重建查询、`indexed_at` 更新和统计查询。
4. 新建 `search` 包，定义请求/响应 DTO、`KnowledgeStore` 接口和 Lucene 字段常量。
5. 实现 `LuceneKnowledgeStore` 的 BM25 index/search/delete/status/rebuild 基础能力。
6. 把文档导入成功、导入失败和删除文档三个路径接入 Lucene 索引更新。
7. 实现 `EmbeddingGateway`，基于 Spring AI `EmbeddingModel` batch embedding 和维度校验。
8. 在 Lucene 索引中加入 `KnnFloatVectorField`，实现 `VECTOR` 搜索。
9. 实现 `HYBRID` 搜索的分数归一化和结果融合。
10. 新增 `IndexController` 和 `SearchController`，开放 `/api/index/status`、`/api/index/rebuild`、`/api/search`。
11. 前端知识库页增加索引状态、重建索引和搜索结果面板。
12. 补充 README 第三阶段启动与 Embedding 配置说明。

## Test Plan

- 后端单元测试：
  - Lucene 文档字段构建。
  - BM25 搜索能按关键词命中文本 chunk。
  - 删除 document id 后旧 chunk 不再命中。
  - 混合检索分数融合排序稳定。
  - Spring AI Embedding 响应维度错误会失败并给出明确错误。
- 后端集成测试：
  - 使用临时 `app.storage.base-dir` 创建 SQLite 和 Lucene index。
  - 导入 Markdown/TXT 后能自动索引并通过 `/api/search` 搜到结果。
  - `POST /api/index/rebuild` 能从 SQLite 重建索引。
  - 修改文件后重新导入，旧 chunk 不残留。
  - 删除文档后 Lucene 和 SQLite 都不再返回该文档。
- 前端验证：
  - 页面能展示索引状态。
  - 点击重建索引后状态刷新。
  - 关键词模式不依赖 Embedding 也可搜索。
  - Embedding 未配置时，向量/混合搜索错误提示明确。
- 构建验证：
  ```powershell
  $env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
  $env:Path="$env:JAVA_HOME\bin;$env:Path"
  mvn test
  npm --prefix cogniNote-agent-front run build
  mvn -Pwith-frontend package
  ```

## Risks And Decisions

- Lucene 10.x 与 JDK 25：项目当前已统一 JDK 25，Lucene 10.x 的 Java 基线符合本项目方向。
- 中文分词：第三阶段先用 `StandardAnalyzer`，这是有意取舍。中文召回质量不是最优，但能保持依赖简单；后续可评估 SmartCN、IK 或自定义 analyzer。
- Embedding 维度：Lucene vector field 要求同字段维度一致，因此必须从配置固定维度；用户换 embedding 模型后需要全量重建索引。
- 同步索引：第三阶段先同步执行，符合当前导入接口同步模型；大目录异步任务和进度条放后续。
- SQLite 与 Lucene 一致性：SQLite 是事实来源，Lucene 可重建；若单文件索引失败，应保留 SQLite 数据并让 `indexed_at` 为空，状态接口提示未索引。

## Assumptions

- 后端环境继续统一使用 JDK 25。
- 第三阶段可以新增 Lucene、Spring AI、Spring AI Alibaba 依赖，但不引入 JPA、MyBatis、Flyway。
- Embedding 先按 Spring AI `EmbeddingModel` 实现，默认 provider 是 Spring AI Alibaba DashScope。
- 第三阶段前端仍是原生 Vue + CSS，不引入 UI 组件库。
- 第三阶段只做搜索结果展示，不生成答案。
