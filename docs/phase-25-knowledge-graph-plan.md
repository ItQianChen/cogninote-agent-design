# 第 25 阶段计划：知识图谱与思维导图

## Summary

第 25 阶段在现有本地知识库和 RAG 能力之上增加知识图谱层：从用户导入的资料 `chunks` 中抽取实体、关系和证据，保存为可重建、可追溯的 SQLite 图谱事实数据，再派生出思维导图和关系图两类前端视图。

本阶段的核心原则是：SQLite 继续作为业务事实来源；Lucene 继续作为可重建检索索引；知识图谱也必须落在 SQLite 中，并且每个节点和关系都能回链到原始 `chunk_id`。前端只消费后端生成的图谱视图，不把模型抽取结果只保存在浏览器状态里。

## 背景

当前项目已经完成文档导入、chunk 切分、SQLite 持久化、Lucene 混合检索和 RAG 问答。这个基础足够支撑知识图谱功能，不需要从零引入独立的文档管线。

外部 GraphRAG / LLM Knowledge Graph Builder 的主流流程基本一致：

1. 把原始资料切成 TextUnit / chunk。
2. 对 chunk 调用模型抽取实体、关系和关键声明。
3. 合并重复实体和关系，保留来源证据。
4. 可选地做社区聚类和社区摘要。
5. 查询或展示时按局部图谱、全局摘要或层级视图裁剪上下文。

CogniNote 第一版不做完整 GraphRAG 查询替代，只先做“知识图谱生成 + 思维导图/关系图展示 + 证据回链”。这样能把数据模型和用户体验跑通，避免一开始把图数据库、社区算法、图谱问答和复杂交互全部塞进同一阶段。

## Goals

- 基于现有 `documents` 和 `chunks` 生成知识图谱。
- 支持按全库、知识库目录、单文档重建图谱。
- 支持增量重建：内容 hash、抽取 prompt 或模型配置未变化的 chunk 不重复调用模型。
- 所有节点、边和摘要都必须保留证据来源，至少能回链到 `chunk_id`。
- 前端提供思维导图视图，用于快速浏览资料结构。
- 前端提供关系图视图，用于查看实体之间的连接。
- 图谱生成进度使用 SSE 推送，SQLite 状态快照作为刷新和断线恢复兜底。
- 不影响现有导入、搜索、RAG 对话和聊天记录结构。

## Non-goals

- 本阶段不引入 Neo4j、NebulaGraph、JanusGraph 等外部图数据库。
- 本阶段不替换 Lucene 检索链路，也不把 GraphRAG 作为默认 RAG 检索器。
- 本阶段不做复杂社区聚类、PageRank、实体 embedding 或图算法排名。
- 本阶段不要求用户手工维护 schema、本体或关系类型白名单。
- 本阶段不把模型抽取结果当作绝对事实；所有图谱内容都应被视为“来自资料的模型结构化结果”。

## 现有基础

- `DocumentIngestionService` 已经负责导入目录、解析文件、切分 chunks，并把结果写入 SQLite。
- `TextChunker` 已经控制 chunk 大小、保留 heading、pageNumber 和 tokenCount，适合作为图谱抽取的最小证据单元。
- `DocumentRepository` 已经能按文档和目录回读 chunks。
- `AiRuntimeFactory` 和 `AiChatRuntime.callText` 已经支持同步模型调用。
- `QueryContextualizerAgent` 已经有“只返回 JSON、后端解析校验、失败降级”的范式。
- 前端 `/knowledge` 页面已有目录管理和检索测试两个面板，适合作为图谱入口。

## 数据模型

新增表建议：

```sql
CREATE TABLE IF NOT EXISTS knowledge_graph_runs (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    status TEXT NOT NULL,
    model_config_id TEXT,
    prompt_version TEXT NOT NULL,
    total_chunk_count INTEGER NOT NULL DEFAULT 0,
    processed_chunk_count INTEGER NOT NULL DEFAULT 0,
    extracted_node_count INTEGER NOT NULL DEFAULT 0,
    extracted_edge_count INTEGER NOT NULL DEFAULT 0,
    failed_chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at INTEGER,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

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

CREATE TABLE IF NOT EXISTS knowledge_graph_chunk_state (
    chunk_id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    content_hash TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    model_config_id TEXT,
    status TEXT NOT NULL,
    error_message TEXT,
    extracted_at INTEGER
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

### 状态语义

`knowledge_graph_runs.status`：

| 状态 | 说明 |
| --- | --- |
| `QUEUED` | 任务已创建，等待执行。 |
| `RUNNING` | 正在抽取或合并图谱。 |
| `CANCELLED` | 用户取消，已停止继续调用模型。 |
| `COMPLETED` | 图谱和派生视图生成完成。 |
| `FAILED` | 任务整体失败。 |

`knowledge_graph_chunk_state.status`：

| 状态 | 说明 |
| --- | --- |
| `PENDING` | 等待抽取。 |
| `SKIPPED` | hash、prompt 和模型配置未变化，复用旧结果。 |
| `EXTRACTED` | 抽取成功。 |
| `FAILED` | 单个 chunk 抽取失败，不阻断整批任务。 |

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
| `KnowledgeGraphService` | 图谱任务入口，负责创建 run、启动后台任务、取消任务、查询状态和读取视图。 |
| `GraphExtractionService` | 按 chunk 调用模型抽取实体和关系，处理失败和增量跳过。 |
| `GraphCanonicalizer` | 规范化实体名、关系类型和去重 key，合并重复节点/边。 |
| `GraphEvidenceService` | 保存节点/边证据，控制 quote 长度，回链 `chunk_id`。 |
| `GraphViewBuilder` | 从图谱事实生成 `MINDMAP` 和 `GRAPH` 两类前端 payload。 |
| `KnowledgeGraphRunPublisher` | 维护运行中任务的 SSE 事件订阅和发布。 |

## 抽取 Prompt 契约

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
- `type` 和关系类型允许模型给出，但后端要归一化为大写 snake case。
- 如果 chunk 没有实体关系，返回空数组。
- 单个 chunk 的节点和边数量需要有上限，避免模型把整段文字拆成噪音图。

## 重建流程

```text
POST /api/knowledge-graphs/rebuild
-> 创建 knowledge_graph_runs 记录
-> 后台执行任务
-> 查询目标范围内的 PARSED chunks
-> 根据 content_hash + prompt_version + model_config_id 判断跳过或抽取
-> 调用 active Chat 模型抽取 JSON
-> 校验 JSON 和证据 quote
-> 写入临时抽取结果
-> 合并 canonical node / edge
-> 保存 evidence
-> 生成 mindmap / graph 视图 payload
-> 更新 run 状态
-> SSE 推送完成事件
```

后台任务第一版可以使用 Spring `TaskExecutor`。不要在 Controller 里同步跑完整抽取流程，否则大目录会阻塞 HTTP 请求并让前端无法取消。

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
data: {"runId":"kg-run-id","processedChunkCount":32,"totalChunkCount":120,"failedChunkCount":1}

event: graph-run-view-ready
data: {"runId":"kg-run-id","viewType":"MINDMAP"}

event: graph-run-completed
data: {"runId":"kg-run-id","status":"COMPLETED","nodeCount":88,"edgeCount":143}

event: graph-run-failed
data: {"runId":"kg-run-id","status":"FAILED","message":"active chat model is not configured"}
```

状态快照兜底：

```http
GET /api/knowledge-graphs/runs/{runId}
```

取消任务：

```http
POST /api/knowledge-graphs/runs/{runId}/cancel
```

前端刷新、SSE 断线或桌面应用恢复后，通过 `GET /runs/{runId}` 读取 SQLite 状态快照。SSE 只是实时通知层，不能作为唯一状态来源。

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

`GRAPH` payload 使用前端图库友好的节点边结构：

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
```

`knowledge-view.vue` 建议从固定左右两栏调整为知识库工作台：

```text
顶部：目录选择 / 全库 / 生成图谱 / 取消
主体 tabs：
  - 目录
  - 检索
  - 图谱
```

第一版视觉重点：

- 默认展示思维导图，不默认渲染过大的全量关系图。
- 图谱生成中显示进度条、已处理 chunks、失败 chunks 和当前阶段。
- 点击节点或关系时，右侧显示证据 quote、文件名、heading、页码和 chunk 预览。
- 大图只展示 Top N 节点和 Top N 边，提供“按节点展开邻居”的局部加载入口。

## 前端图库选择

第一版推荐：

- 思维导图：`markmap-lib` + `markmap-view`。
- 关系图：优先预留 payload 契约，后续再引入 `cytoscape` 或 `@antv/g6`。

原因：

- `markmap` 很适合把后端生成的层级 Markdown 渲染成脑图，和“资料生成思维导图”的需求最贴近。
- 关系图交互复杂度更高，需要处理布局、缩放、筛选、选中、证据面板和性能边界。
- G6 在 Vue 中不能直接传 reactive data 给图实例；如果使用 G6，组件内部必须把 Pinia 数据转成普通对象快照后再喂给图实例。

## 与现有功能的关系

- 文档导入成功后，不自动调用图谱生成。图谱生成有模型成本，必须由用户显式触发。
- 删除知识库目录时，应级联删除该目录范围内的图谱节点、边、证据、视图和 run 记录。
- 停用知识库目录时，图谱数据可以保留，但默认查询和展示不包含停用目录。
- 重建 Lucene 索引不自动重建知识图谱；两者是不同派生物。
- 后续 GraphRAG 查询可以复用图谱数据，但不在本阶段默认接入聊天 RAG。

## 错误处理

- 未配置 active Chat 模型时，图谱重建直接失败并提示配置模型。
- 单个 chunk 模型返回非法 JSON 时，记录 chunk 失败，不中断整个 run。
- 模型返回的 quote 如果无法在 chunk 原文中找到，丢弃该条证据或降低 confidence，不直接入库为强证据。
- SSE 连接断开不取消任务；前端重新连接或读取 run 快照。
- 用户取消任务后，后台应停止继续调模型，但已成功写入的中间结果需要按 run 状态清理或标记为不完整。

## Test Plan

- `mvn test`
- `npm --prefix cogniNote-agent-front run build`
- 数据库初始化能创建图谱相关表和索引。
- 未配置 Chat 模型时，`POST /api/knowledge-graphs/rebuild` 返回明确错误或 run 失败状态。
- 小知识库目录能完成图谱生成，节点、边、证据数量正确。
- 抽取结果每个节点/边至少能追溯到一个 `chunk_id`。
- chunk 内容未变化时，重复重建能跳过已抽取 chunk。
- 修改 prompt_version 后，相关 chunk 会重新抽取。
- 单个 chunk 非法 JSON 不影响其他 chunks 继续处理。
- SSE 能收到 started、progress、completed 或 failed 事件。
- 前端刷新后能通过 `GET /runs/{runId}` 恢复任务状态。
- 取消任务后不再继续调用模型。
- 删除知识库目录时，对应图谱数据不会残留到默认视图。
- 思维导图视图能渲染空状态、生成中、完成和失败状态。

## Assumptions

- 图谱抽取默认使用 active Chat 模型，不新增独立 `GRAPH_EXTRACTION` 模型角色。
- 第一版图谱只做资料内结构化，不声称自动发现外部事实。
- 用户更需要“理解资料结构”和“看关系证据”，而不是一开始就做完整 GraphRAG 问答。
- SQLite 足以支撑个人知识库规模；等图谱数据量真的超过本地 SQLite 可接受范围，再评估外部图数据库。

## 资料依据

- [Microsoft GraphRAG](https://microsoft.github.io/graphrag/)：GraphRAG 将原始文本切分为 TextUnits，抽取实体、关系和声明，再做社区层级与摘要。
- [Neo4j Knowledge Graph Extraction and Challenges](https://neo4j.com/blog/developer/knowledge-graph-extraction-challenges/)：LLM 图谱构建常见流程包括 ingestion、chunking、embedding、entity extraction 和 post-processing。
- [markmap docs](https://markmap.js.org/docs/markmap)：markmap 可把 Markdown 层级结构渲染成交互式思维导图。
- [G6 Vue integration](https://g6.antv.vision/en/manual/getting-started/integration/vue/)：G6 在 Vue 中不应直接接收 reactive data。
- [Cytoscape.js](https://js.cytoscape.org/)：成熟的前端图可视化和图分析库，适合后续关系图视图。
