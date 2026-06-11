# 第 25 阶段计划：知识图谱与思维导图

## Summary

第 25 阶段在现有本地知识库和 RAG 能力之上增加知识图谱层：从用户导入的资料 `chunks` 中抽取实体、关系和证据，保存为可重建、可追溯的 SQLite 图谱事实数据，再派生出思维导图和关系图两类前端视图。

实施状态：已落地。后端新增知识图谱表、Mapper、Repository、Service、Controller 和 SSE run 进度推送；前端在知识库工作台新增“知识图谱” tab，提供重建、进度恢复、思维导图、关系图、邻接列表和证据抽屉。抽取 Prompt 已从代码迁移到 `src/main/resources/cogninote-prompts.yaml`。

本阶段的核心原则是：SQLite 继续作为业务事实来源；Lucene 继续作为可重建检索索引；知识图谱也必须落在 SQLite 中，并且每个节点和关系都能回链到原始 `chunk_id`。前端只消费后端生成的图谱视图，不把模型抽取结果只保存在浏览器状态里。

图谱数据分两层，这是本阶段最重要的结构决策：

- **抽取缓存层**：模型对单个 chunk 的原始抽取结果，按 `chunk_id` 缓存，与 scope 无关，跨范围复用。模型调用是唯一昂贵操作，缓存让它只发生一次。
- **图谱派生层**：按 scope 合并出的节点、边、证据和视图。派生层永远可以从缓存层全量重建，merge 是纯本地计算，便宜且幂等。

## 背景

当前项目已经完成文档导入、chunk 切分、SQLite 持久化、Lucene 混合检索和 RAG 问答。这个基础足够支撑知识图谱功能，不需要从零引入独立的文档管线。

外部 GraphRAG / LLM Knowledge Graph Builder 的主流流程基本一致：

1. 把原始资料切成 TextUnit / chunk。
2. 对 chunk 调用模型抽取实体、关系和关键声明。
3. 合并重复实体和关系，保留来源证据。
4. 可选地做社区聚类和社区摘要。
5. 查询或展示时按局部图谱、全局摘要或层级视图裁剪上下文。

GraphRAG 的增量索引正是把抽取结果缓存在 TextUnit 级、图作为可全量重建的派生物，本计划的"抽取缓存层 + 派生层"分层与其一致。

CogniNote 第一版不做完整 GraphRAG 查询替代，只先做“知识图谱生成 + 思维导图/关系图展示 + 证据回链”。这样能把数据模型和用户体验跑通，避免一开始把图数据库、社区算法、图谱问答和复杂交互全部塞进同一阶段。

## Goals

- 基于现有 `documents` 和 `chunks` 生成知识图谱。
- 支持按全库、知识库目录、单文档重建图谱。
- 支持增量重建：模型抽取结果按 `chunk_id` 缓存；内容 hash、抽取 prompt 或模型配置未变化的 chunk 不重复调用模型，且缓存跨 scope 复用（先建目录图谱再建全库图谱时，重叠 chunk 不再付费）。
- 所有节点、边和摘要都必须保留证据来源，至少能回链到 `chunk_id`。
- 前端提供思维导图视图，用于快速浏览资料结构。
- 前端提供关系图视图，用于查看实体之间的连接；同时提供列表（邻接表）视图作为可访问性替代与大图兜底。
- 图谱生成进度使用 SSE 推送，SQLite 状态快照作为刷新和断线恢复兜底。
- 同一 scope 同时只允许一个进行中的 run；应用重启后遗留的进行中 run 会被标记失败，不会永久卡住。
- 不影响现有导入、搜索、RAG 对话和聊天记录结构。

## Non-goals

- 本阶段不引入 Neo4j、NebulaGraph、JanusGraph 等外部图数据库。
- 本阶段不替换 Lucene 检索链路，也不把 GraphRAG 作为默认 RAG 检索器。
- 本阶段不做复杂社区聚类、PageRank、实体 embedding 或图算法排名。
- 本阶段不要求用户手工维护 schema、本体或关系类型白名单。
- 本阶段不把模型抽取结果当作绝对事实；所有图谱内容都应被视为“来自资料的模型结构化结果”。
- 本阶段实体去重只做字符串规范化（大小写、空白、全半角），不做 embedding 相似度或 LLM 二次归并的实体消歧。同义实体（如 “IBM” 与 “I.B.M.”）可能分裂为多个节点，`mention_count` 和 degree 会因此偏低，这是已知且接受的 v1 局限，LLM 二次归并列入后续阶段。
- 本阶段抽取串行执行，不做并发模型调用；有界并发列为后续优化。

## 现有基础

- `DocumentIngestionService` 已经负责导入目录、解析文件、切分 chunks，并把结果写入 SQLite。
- `TextChunker` 已经控制 chunk 大小、保留 heading、pageNumber 和 tokenCount，适合作为图谱抽取的最小证据单元。
- `chunks` 表已有 `content_hash NOT NULL`、`heading`、`page_number`、`token_count` 字段，可直接作为抽取缓存失效判断和证据展示字段，无需迁移。
- `DocumentRepository` 已经能按文档和目录回读 chunks。
- `AiRuntimeFactory` 和 `AiChatRuntime.callText` 已经支持同步模型调用。
- `QueryContextualizerAgent` 已经有“只返回 JSON、后端解析校验、失败降级”的范式。
- Prompt 统一由 `cogninote-prompts.yaml` 维护；`application.yaml` 只通过 `spring.config.import` 导入该专用文件，避免业务运行配置和大段 Prompt 混杂。
- 后端 `ChatController` 已有 `SseEmitter` + `TEXT_EVENT_STREAM` 的 SSE 写法；前端 `chat-stream.js` 已有 `readSseStream` 流式解析工具，图谱进度 SSE 直接复用。
- `source-inspector.vue` 与 `getDocumentChunk` API 已实现“quote + 文件名 + heading + 页码 + chunk 详情弹窗”的完整交互，图谱证据面板复用该模式。
- 第 24 阶段已把 `/knowledge` 改造为 tab 工作台（`knowledge-workbench-view.vue` 的 `panelOptions`：资料管理/检索测试），图谱作为第三个 tab 接入。

## 基础设施约束（实现必须遵守）

两个项目事实决定了本阶段的实现纪律：

1. **SQLite 连接池大小为 1**（`SQLiteDataSourceConfig.SQLITE_POOL_SIZE = 1`）。后台图谱任务的每次 DB 访问都会和前台请求（聊天、检索、导入）竞争唯一连接，长事务会卡死整个应用。因此：
   - 模型调用（单次数秒）期间绝不持有连接或事务。
   - 进度计数在内存中维护，SSE 直接从内存推送；落库节流（如每 10 个 chunk 或每 5 秒一次）。
   - 抽取结果分小批短事务写入，禁止整 run 长事务。
2. **本项目未开启 SQLite `foreign_keys` PRAGMA**（JDBC URL 无任何 pragma 配置，sqlite-jdbc 默认关闭外键），所有 `ON DELETE CASCADE` 声明都不会生效。现有代码的级联删除全部是显式 SQL（如 `deleteChunksByKnowledgeFolderId`）。图谱表的 FK 声明仅作结构文档；数据清理必须按现有模式写显式 mapper 删除。

## 数据模型

新增表建议：

```sql
-- run 记录：防重入检查 + 状态快照 + 历史
CREATE TABLE IF NOT EXISTS knowledge_graph_runs (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    status TEXT NOT NULL,
    model_config_id TEXT,
    prompt_version TEXT NOT NULL,
    total_chunk_count INTEGER NOT NULL DEFAULT 0,
    processed_chunk_count INTEGER NOT NULL DEFAULT 0,
    skipped_chunk_count INTEGER NOT NULL DEFAULT 0,
    extracted_node_count INTEGER NOT NULL DEFAULT 0,
    extracted_edge_count INTEGER NOT NULL DEFAULT 0,
    failed_chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at INTEGER,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 抽取缓存层：模型对单个 chunk 的原始抽取结果，与 scope 无关，跨范围复用
CREATE TABLE IF NOT EXISTS knowledge_graph_chunk_extractions (
    chunk_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    model_config_id TEXT,
    status TEXT NOT NULL,
    extraction_json TEXT,
    error_message TEXT,
    extracted_at INTEGER
);

-- 以下为图谱派生层：按 scope 合并的结果，永远可从缓存层全量重建

CREATE TABLE IF NOT EXISTS knowledge_graph_nodes (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    canonical_name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    node_type TEXT NOT NULL,
    description TEXT,
    confidence REAL NOT NULL DEFAULT 0,
    mention_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- FK 声明仅作结构文档，本项目未开启 foreign_keys，删除靠显式 SQL
CREATE TABLE IF NOT EXISTS knowledge_graph_edges (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    source_node_id TEXT NOT NULL,
    target_node_id TEXT NOT NULL,
    relation_type TEXT NOT NULL,
    description TEXT,
    confidence REAL NOT NULL DEFAULT 0,
    mention_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (source_node_id) REFERENCES knowledge_graph_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_node_id) REFERENCES knowledge_graph_nodes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_graph_evidence (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    node_id TEXT,
    edge_id TEXT,
    document_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    quote TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_graph_views (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    view_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    generated_from_run_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

必要索引（沿用现有 `DatabaseSchemaInitializer` 为每张表建索引的惯例）：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS idx_kg_nodes_scope_canonical
    ON knowledge_graph_nodes(scope_type, scope_id, canonical_name, node_type);
CREATE INDEX IF NOT EXISTS idx_kg_edges_scope
    ON knowledge_graph_edges(scope_type, scope_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kg_edges_scope_triple
    ON knowledge_graph_edges(scope_type, scope_id, source_node_id, target_node_id, relation_type);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_node ON knowledge_graph_evidence(node_id);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_edge ON knowledge_graph_evidence(edge_id);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_chunk ON knowledge_graph_evidence(chunk_id);
CREATE INDEX IF NOT EXISTS idx_kg_runs_scope_status
    ON knowledge_graph_runs(scope_type, scope_id, status);
CREATE INDEX IF NOT EXISTS idx_kg_views_scope
    ON knowledge_graph_views(scope_type, scope_id, view_type);
```

两个唯一索引同时就是 merge 的去重 key：节点按 `(scope, canonical_name, node_type)` 合并，边按 `(scope, source, target, relation_type)` 合并。

### 状态语义

`knowledge_graph_runs.status`：

| 状态 | 说明 |
| --- | --- |
| `QUEUED` | 任务已创建，等待执行。 |
| `RUNNING` | 正在抽取或合并图谱。 |
| `CANCELLED` | 用户取消，已停止继续调用模型。 |
| `COMPLETED` | 图谱和派生视图生成完成。 |
| `FAILED` | 任务整体失败，或应用重启时被孤儿清理标记。 |

应用启动时把遗留的 `QUEUED` / `RUNNING` run 统一标记为 `FAILED`（孤儿清理）。这是 Tauri 桌面应用，用户随时可能关窗口；不做启动清理，run 会永久卡在进行中。实现上复用 `DatabaseSchemaInitializer` 的启动清理先例（`cleanupSoftDeletedChatSessions`），但放在 graph 包内独立的 `ApplicationReadyEvent` 监听器中。

`knowledge_graph_chunk_extractions.status`：

| 状态 | 说明 |
| --- | --- |
| `EXTRACTED` | 抽取成功，`extraction_json` 可复用（含“无实体关系”的空结果）。 |
| `FAILED` | 模型调用或 JSON 校验失败，下次 rebuild 自动重试。 |

“跳过”不再是持久化状态：run 执行时发现缓存行的 `content_hash + prompt_version + model_config_id` 与当前一致且 status 为 `EXTRACTED`，即跳过模型调用，计入该 run 的 `skipped_chunk_count`。跳过的是模型调用，不是合并——merge 永远从缓存全量重跑。

### 缓存失效与垃圾回收

- chunk 内容变化（`content_hash` 不同）、`prompt_version` 或 `model_config_id` 变化时，该 chunk 重新抽取并覆盖缓存行。
- 文档重新导入会重建 chunk 行（新 chunk_id），旧缓存行成为孤儿。为不侵入现有导入流程，rebuild 开始时顺带 GC：删除 `chunk_id` 已不存在于 `chunks` 表的缓存行。
- 删除知识库目录时显式删除对应缓存行（见“与现有功能的关系”）。

## 后端模块

建议新增包：

```text
com.itqianchen.agentdesign.domain.graph
com.itqianchen.agentdesign.dto.graph
com.itqianchen.agentdesign.mapper.graph
com.itqianchen.agentdesign.repository.graph
com.itqianchen.agentdesign.service.graph
com.itqianchen.agentdesign.controller.graph
```

核心服务：

| 类型 | 职责 |
| --- | --- |
| `KnowledgeGraphService` | 图谱任务入口：防重入检查（同 scope 已有 QUEUED/RUNNING run 时直接返回该 run）、创建 run、启动后台任务、取消任务、查询状态和读取视图。 |
| `GraphExtractionService` | 按 chunk 串行调用模型，维护抽取缓存；每个 chunk 之间检查取消标志；模型调用期间不持有 DB 连接。 |
| `GraphCanonicalizer` | 字符串规范化（trim、小写、全半角、空白折叠）实体名与关系类型，生成去重 key。 |
| `GraphMergeService` | 从缓存全量合并出 scope 的节点/边/证据：quote 归一化校验、孤儿边丢弃、mention_count 与 confidence 聚合、证据 quote 长度控制。 |
| `GraphViewBuilder` | 从图谱事实生成 `MINDMAP` 和 `GRAPH` 两类前端 payload。 |
| `KnowledgeGraphRunPublisher` | 维护运行中任务的 SSE 事件订阅和发布，进度数据来自内存计数器。 |
| `KnowledgeGraphStartupCleaner` | 应用启动时把遗留 QUEUED/RUNNING run 标记为 FAILED。 |

## 抽取 Prompt 契约

配置入口：

```yaml
app:
  knowledge-graph:
    prompts:
      extraction:
        version: kg-extract-v1
        system: ...
        user: ...
```

`KnowledgeGraphPromptProperties` 会在启动时校验 `version/system/user` 不为空，并要求 user 模板包含 `{documentName}`、`{chunkId}`、`{heading}`、`{pageNumber}` 和 `{content}`。`version` 会写入 `knowledge_graph_runs.prompt_version` 和 `knowledge_graph_chunk_extractions.prompt_version`，也是缓存复用判断的一部分；修改抽取语义时必须同步升级版本，否则旧缓存会继续命中。

模型必须只返回 JSON。后端只接受结构化字段，不从自然语言解释中猜结果。

```json
{
  "nodes": [
    {
      "name": "CogniNote",
      "type": "PRODUCT",
      "description": "本地优先的个人知识库问答应用",
      "confidence": 0.92,
      "quote": "CogniNote Agent 是一个本地优先的个人知识库问答应用。"
    }
  ],
  "edges": [
    {
      "source": "CogniNote",
      "target": "Lucene",
      "type": "USES",
      "description": "CogniNote 使用 Lucene 建立关键词/向量混合检索索引",
      "confidence": 0.88,
      "quote": "使用 Lucene 建立关键词/向量混合检索索引"
    }
  ]
}
```

抽取规则：

- `name` 必须来自 chunk 语义，不允许凭空引入外部实体。
- `quote` 必须是 chunk 原文中的短片段，用于证据展示。
- `quote` 校验采用归一化后的包含匹配（忽略空白与标点差异），因为模型经常微调原文空白；严格 `contains` 会误杀大量有效证据。匹配失败的证据丢弃或降低 confidence，不作为强证据入库。
- `type` 和关系类型允许模型给出，但后端要归一化为大写 snake case。
- `edges` 的 `source` / `target` 必须能解析到本次抽取返回的 `nodes`（按规范化后名称匹配）；解析不到端点的边直接丢弃，不入库。
- 如果 chunk 没有实体关系，返回空数组（缓存为 EXTRACTED 空结果，同样可复用）。
- 单个 chunk 的节点和边数量需要有上限，避免模型把整段文字拆成噪音图。

## 重建流程

```text
POST /api/knowledge-graphs/rebuild
-> 防重入：同 scope 存在 QUEUED/RUNNING run 时，直接返回该 run（幂等，不报错不新建）
-> 创建 knowledge_graph_runs 记录
-> 后台任务（Spring TaskExecutor）：
   1. GC：删除 chunk_id 已不存在于 chunks 表的抽取缓存行
   2. 查询目标范围内的 PARSED chunks
   3. 抽取阶段（串行，可取消）：
      对每个 chunk：
        缓存命中（hash + prompt_version + model_config 一致且 EXTRACTED）-> 跳过模型调用
        否则调用 active Chat 模型 -> 校验 JSON -> 写入/覆盖缓存行（短事务）
      进度在内存中累加，SSE 实时推送，落库节流
      每个 chunk 之间检查取消标志
   4. 合并阶段（快速本地计算，不提供取消窗口）：
      删除该 scope 旧的 nodes / edges / evidence / views
      从缓存全量 merge：规范化 -> 节点/边按唯一 key 聚合 -> quote 校验 -> 证据落库
      生成 mindmap / graph 视图 payload
   5. 更新 run 状态
   6. SSE 推送完成事件
```

抽取阶段取消或失败时，**旧图谱原样保留**：删除旧图谱发生在合并阶段开头，而合并只在抽取完成后进行。已写入的抽取缓存永远有效，下次 rebuild 直接复用，所以取消不浪费已花费的模型成本。

后台任务第一版使用 Spring `TaskExecutor`。不要在 Controller 里同步跑完整抽取流程，否则大目录会阻塞 HTTP 请求并让前端无法取消。

## MINDMAP 树派生规则

图谱事实是网状结构，思维导图是树，必须定义确定性的派生规则。第一版用资料自身结构作骨架，不额外调用模型：

```text
根节点：scope 名称（目录名 / 全库 / 文档名）
一级：文档（按文件名）
二级：heading（chunks.heading，保持文档内出现顺序）
三级：实体（按证据归属挂到对应 heading 下；跨多处出现的实体挂在 mention 最多的 heading）
```

- 每层做数量裁剪：heading 下实体按 `mention_count` 取 Top N，避免大目录生成不可读的巨型导图。
- 选择规则派生而非模型生成的原因：确定性、零额外模型成本、与“浏览资料结构”的语义吻合。
- 后续增强（不在本阶段）：用模型对全图谱做一次“全局摘要 -> 层级大纲”，作为可选的智能导图。

## SSE 进度与恢复

图谱生成不使用纯轮询。推荐接口：

```http
POST /api/knowledge-graphs/rebuild
```

请求：

```json
{
  "scopeType": "KNOWLEDGE_FOLDER",
  "scopeId": "folder-id"
}
```

响应：

```json
{
  "runId": "kg-run-id",
  "status": "QUEUED"
}
```

若同 scope 已有进行中 run，响应返回该 run 的 `runId` 与当前 `status`，前端直接订阅它的事件流，不视为错误。

实时进度：

```http
GET /api/knowledge-graphs/runs/{runId}/events
Accept: text/event-stream
```

事件：

```text
event: graph-run-started
data: {"runId":"kg-run-id","status":"RUNNING","totalChunkCount":120}

event: graph-run-progress
data: {"runId":"kg-run-id","processedChunkCount":32,"skippedChunkCount":18,"totalChunkCount":120,"failedChunkCount":1}

event: graph-run-view-ready
data: {"runId":"kg-run-id","viewType":"MINDMAP"}

event: graph-run-completed
data: {"runId":"kg-run-id","status":"COMPLETED","nodeCount":88,"edgeCount":143}

event: graph-run-failed
data: {"runId":"kg-run-id","status":"FAILED","message":"active chat model is not configured"}
```

进度事件的数据来自内存计数器（`KnowledgeGraphRunPublisher`），不依赖每个 chunk 写库，避免与唯一 SQLite 连接竞争。

状态快照兜底：

```http
GET /api/knowledge-graphs/runs/{runId}
```

取消任务：

```http
POST /api/knowledge-graphs/runs/{runId}/cancel
```

前端刷新、SSE 断线或桌面应用恢复后，通过 `GET /runs/{runId}` 读取 SQLite 状态快照。SSE 只是实时通知层，不能作为唯一状态来源。前端 SSE 解析复用 `chat-stream.js` 的 `readSseStream` 工具。

应用（或桌面端）重启后，启动清理会把遗留 RUNNING/QUEUED run 标记为 FAILED；前端读取快照时按失败态展示，提供重新生成入口。

## 查询与视图 API

```http
GET /api/knowledge-graphs/status?scopeType=KNOWLEDGE_FOLDER&scopeId=...
GET /api/knowledge-graphs/view?scopeType=KNOWLEDGE_FOLDER&scopeId=...&viewType=MINDMAP
GET /api/knowledge-graphs/view?scopeType=KNOWLEDGE_FOLDER&scopeId=...&viewType=GRAPH
GET /api/knowledge-graphs/nodes/{id}/evidence
GET /api/knowledge-graphs/edges/{id}/evidence
```

`MINDMAP` payload 第一版可以直接返回 Markdown：

```json
{
  "viewType": "MINDMAP",
  "markdown": "# CogniNote\n\n## 本地知识库\n\n### 文档导入\n\n### Lucene 检索\n"
}
```

`GRAPH` payload 使用前端图库友好的节点边结构，同时驱动关系图和列表（邻接表）两种渲染，无需单独 API：

```json
{
  "viewType": "GRAPH",
  "nodes": [
    {"id": "node-1", "label": "CogniNote", "type": "PRODUCT", "degree": 12}
  ],
  "edges": [
    {"id": "edge-1", "source": "node-1", "target": "node-2", "label": "USES", "weight": 3}
  ]
}
```

## 前端改动

新增文件：

```text
cogniNote-agent-front/src/api/knowledge-graph-api.js
cogniNote-agent-front/src/stores/knowledge-graph.js
cogniNote-agent-front/src/components/knowledge-graph-panel.vue
cogniNote-agent-front/src/components/mindmap-viewer.vue
cogniNote-agent-front/src/components/graph-viewer.vue
cogniNote-agent-front/src/components/graph-adjacency-list.vue
cogniNote-agent-front/src/components/graph-evidence-drawer.vue
```

入口：第 24 阶段已落地 tab 工作台，本阶段只在 `knowledge-workbench-view.vue` 的 `panelOptions` 增加第三项（id `graph`、label “知识图谱”、icon 用 lucide `Network`，与现有 `FolderOpen` / `Search` 同族同风格）。不放顶层导航——图谱是知识库资料的派生物，与“资料管理”“检索测试”同级，不是独立域。

面板布局：

```text
工具栏：范围选择（全库 / 目录下拉） · 视图切换（思维导图 | 关系图 | 列表，复用 segmented-control） · 生成 / 取消 · 上次生成时间
主区四态状态机：
  - 空态：说明文案 + 生成按钮
  - 生成中：进度条（processed/total）+ 跳过/失败计数 + 取消按钮
  - 完成：渲染当前视图
  - 失败：错误原因 + 恢复路径（如“未配置 Chat 模型 -> 去设置”，复用现有 embeddingReady 警告条 + 设置跳转模式）
证据侧栏：点击节点/边滑出；实体名 + 类型 chip + 描述 + 证据列表（quote、文件名、heading、页码）；
  点击证据打开 chunk 详情弹窗（复用 getDocumentChunk API 与 source-inspector 的交互模式）
```

状态归属：

- run 状态、进度、视图数据全部放 Pinia store（`knowledge-graph.js`），不放组件局部状态。用户生成中切到其他 tab 再切回，进度不丢；SSE 断线后由 store 负责快照恢复。生成中不阻塞工作台其他 tab。

第一版视觉重点：

- 默认展示思维导图，不默认渲染过大的全量关系图。
- 关系图规模边界：默认只渲染 Top 50–100 节点（该规模 SVG 渲染足够），提供“按节点展开邻居”的局部加载入口；超过 500 节点的全图渲染明确不支持，引导用户改用列表视图或缩小范围。
- 列表（邻接表）视图：`节点 A -> 关系 -> 节点 B -> 证据数` 表格。它同时是网络图的可访问性替代（网络图对屏幕阅读器基本不可用，规范要求永远提供列表替代）和大图兜底。
- 图谱生成中显示进度条、已处理 chunks、跳过 chunks、失败 chunks 和当前阶段。
- 节点类型用分类色板、边用低饱和色、选中/路径高亮用强调色；具体色值取自项目现有 design tokens（第 24 阶段刚统一过），不引入新色板。

## 前端图库选择

第一版推荐：

- 思维导图：`markmap-lib` + `markmap-view`。
- 关系图：优先预留 payload 契约，后续再引入 `cytoscape` 或 `@antv/g6`。

原因：

- 项目已依赖 mermaid v11（`ai-code-block.vue` 有完整渲染管线），mermaid 原生支持 mindmap 语法，是零新增依赖的备选。但 mermaid mindmap 是静态渲染，无折叠/展开/聚焦交互；markmap 支持交互折叠、fit 视图与缩放，对“浏览大型资料结构”是核心体验，所以仍选 markmap。若希望先零依赖走通端到端，可用 mermaid 作临时降级路径，payload 契约（Markdown 层级）不变。
- 关系图交互复杂度更高，需要处理布局、缩放、筛选、选中、证据面板和性能边界。
- G6 在 Vue 中不能直接传 reactive data 给图实例；如果使用 G6，组件内部必须把 Pinia 数据转成普通对象快照后再喂给图实例。Cytoscape 同理。

## 与现有功能的关系

- 文档导入成功后，不自动调用图谱生成。图谱生成有模型成本，必须由用户显式触发。
- 删除知识库目录时，按现有显式删除模式级联清理：该目录 scope 的 nodes / edges / evidence / views / runs，以及目录下所有 chunk 的抽取缓存行。不依赖 FK 级联（本项目未开启 foreign_keys）。
- 文档重新导入后，旧 chunk 的抽取缓存成为孤儿，由下次 rebuild 开头的 GC 清理，不侵入导入流程。
- 合并阶段对 scope 旧图谱做全删重建，因此旧 run 的派生数据不会无限累积；runs 表只保留历史记录行。
- 停用知识库目录时，图谱数据可以保留，但默认查询和展示不包含停用目录。
- 重建 Lucene 索引不自动重建知识图谱；两者是不同派生物。
- 后续 GraphRAG 查询可以复用图谱数据，但不在本阶段默认接入聊天 RAG。

## 错误处理

- 未配置 active Chat 模型时，图谱重建直接失败并提示配置模型。
- 同 scope 已有进行中 run 时，rebuild 幂等返回该 run，不报错、不创建新 run。
- 单个 chunk 模型返回非法 JSON 时，缓存行记 FAILED，不中断整个 run；下次 rebuild 自动重试该 chunk。
- 模型返回的 quote 归一化后仍无法在 chunk 原文中匹配时，丢弃该条证据或降低 confidence，不直接入库为强证据。
- 边的端点无法解析到本次抽取的节点时，丢弃该边。
- SSE 连接断开不取消任务；前端重新连接或读取 run 快照。
- 用户在抽取阶段取消：停止继续调用模型，run 标记 CANCELLED；已写入的抽取缓存保留（它们是有效缓存，不是垃圾），旧图谱视图原样保留。
- 应用重启后遗留的 QUEUED/RUNNING run 在启动时被标记为 FAILED。

## Test Plan

- `mvn test`
- `npm --prefix cogniNote-agent-front run build`
- 数据库初始化能创建图谱相关表和索引。
- 未配置 Chat 模型时，`POST /api/knowledge-graphs/rebuild` 返回明确错误或 run 失败状态。
- 小知识库目录能完成图谱生成，节点、边、证据数量正确。
- 抽取结果每个节点/边至少能追溯到一个 `chunk_id`。
- chunk 内容未变化时，重复重建跳过模型调用，`skipped_chunk_count` 统计正确。
- 先建目录图谱再建全库图谱，重叠 chunk 不重复调用模型（缓存跨 scope 复用）。
- 修改 prompt_version 后，相关 chunk 会重新抽取。
- 单个 chunk 非法 JSON 不影响其他 chunks 继续处理，缓存行记 FAILED。
- 同 scope RUNNING 时再次 rebuild 返回进行中 run，不创建新 run。
- 模拟应用重启（直接调用启动清理），遗留 RUNNING run 被标记为 FAILED。
- 抽取阶段取消后：不再调用模型，旧图谱视图仍可正常读取。
- 文档重新导入后，孤儿抽取缓存行被 rebuild GC 清理。
- quote 与原文存在空白/标点差异时仍能通过归一化匹配；完全无法匹配的证据被丢弃。
- 端点无法解析的边被丢弃，不产生孤儿边。
- SSE 能收到 started、progress、completed 或 failed 事件。
- 前端刷新后能通过 `GET /runs/{runId}` 恢复任务状态。
- 删除知识库目录时，scope 图谱数据与对应抽取缓存均不残留。
- 思维导图视图能渲染空状态、生成中、完成和失败状态。
- 列表（邻接表）视图能渲染节点-关系-节点表格。

## Assumptions

- 图谱抽取默认使用 active Chat 模型，不新增独立 `GRAPH_EXTRACTION` 模型角色。
- 抽取串行执行可接受：个人知识库规模（数百 chunk）在分钟级完成，显式触发的后台任务允许等待；串行同时简化取消逻辑、避开模型限流和 SQLite 单连接竞争。
- 第一版图谱只做资料内结构化，不声称自动发现外部事实。
- 用户更需要“理解资料结构”和“看关系证据”，而不是一开始就做完整 GraphRAG 问答。
- SQLite 足以支撑个人知识库规模；等图谱数据量真的超过本地 SQLite 可接受范围，再评估外部图数据库。

## 资料依据

- [Microsoft GraphRAG](https://microsoft.github.io/graphrag/)：GraphRAG 将原始文本切分为 TextUnits，抽取实体、关系和声明，再做社区层级与摘要；其增量索引把抽取结果缓存在 TextUnit 级、图作为派生物，本计划的两层数据模型与其一致。
- [Neo4j Knowledge Graph Extraction and Challenges](https://neo4j.com/blog/developer/knowledge-graph-extraction-challenges/)：LLM 图谱构建常见流程包括 ingestion、chunking、embedding、entity extraction 和 post-processing。
- [Extract, Define, Canonicalize: An LLM-based Framework for Knowledge Graph Construction (EMNLP 2024)](https://aclanthology.org/2024.emnlp-main.548/)：实体规范化/消歧是 LLM 图谱质量的决定因素；本阶段 v1 限定为字符串规范化并显式声明局限。
- [markmap docs](https://markmap.js.org/docs/markmap)：markmap 可把 Markdown 层级结构渲染成交互式思维导图。
- [mermaid mindmap](https://mermaid.js.org/syntax/mindmap.html)：mermaid v11 原生支持 mindmap 语法（项目已有该依赖），但为静态渲染，无交互折叠，故仅作降级备选。
- [G6 Vue integration](https://g6.antv.vision/en/manual/getting-started/integration/vue/)：G6 在 Vue 中不应直接接收 reactive data。
- [Cytoscape.js](https://js.cytoscape.org/)：成熟的前端图可视化和图分析库，适合后续关系图视图。
