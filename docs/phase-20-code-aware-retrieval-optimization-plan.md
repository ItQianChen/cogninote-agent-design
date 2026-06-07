# 第二十阶段计划：代码友好的知识库检索准确率优化

## Summary

第 20 阶段把知识库检索从“基础 BM25 + Vector 混合检索”升级为更适合中文正文、代码笔记和流程图笔记的检索方案。核心原则是：SQLite 中的 chunk 继续保存可展示、可喂给模型的原文；Lucene 可以使用派生索引文本来提升召回；Embedding 调用在内部区分文档向量化和查询向量化。

本阶段不引入 reranker，不更换向量库，不改变 REST API，不改变 SQLite schema。

## Key Changes

- `TextChunker` 保护 Markdown fenced code block、Mermaid、PlantUML 和常见代码块，不再压缩代码块内部缩进、tab 和换行。
- chunk 切分按普通文本块和 protected block 分层处理，普通正文保留重叠窗口，代码/流程图块尽量不切断；超大 protected block 拆分时补齐 fence 标记。
- Lucene 使用 `SmartChineseAnalyzer` 处理正文，使用 `StandardAnalyzer` 处理代码/标识符字段。
- 新增派生索引文本生成器，从代码块和流程图中提取语言名、类名、函数名、变量名、路径、异常名、图类型和节点文本。
- Lucene 存储字段仍不作为展示事实来源；RAG 展示和模型上下文仍从 SQLite 的 `chunks.content` 回读原文。
- HYBRID 候选集改为 `max(topK * 8, 60)`。
- 混合排序从 min-max 归一化加权改为加权 RRF，默认 `bm25Weight=0.45`、`vectorWeight=0.55`、`rrfK=60`。
- `EmbeddingGateway` / `AiEmbeddingRuntime` 拆分为 `embedDocuments` 和 `embedQuery`。
- DashScope Embedding 通过 Spring AI Alibaba 的 `DashScopeEmbeddingOptions.textType` 分别传递 `document` / `query`，并按完整 key 缓存两个模型实例。
- OpenAI-compatible 继续使用 Spring AI OpenAI runtime 的标准 `/embeddings` 请求，不发送非标准 `text_type` 字段。

## 检索效果

第 20 阶段的目标不是让模型“更会写代码”，而是让知识库里已经存在的中文笔记、代码笔记和流程图笔记更容易被检索召回。检索结果展示和 RAG 上下文仍使用 SQLite 中的原文 chunk，因此代码缩进、fenced code block 和 Mermaid / PlantUML 内容不应该因为索引增强而变形。

### 中文搜索效果

中文正文不再完全依赖通用英文分词器。BM25 正文字段使用 `SmartChineseAnalyzer`，更适合搜索中文短语、中文技术说明和中文标题。

可验证的查询示例：

- 导入包含“知识库重建索引”的笔记后，搜索 `知识库重建索引`、`重建索引` 应命中对应 chunk。
- 导入包含“桌面打包失败”的排障记录后，搜索 `桌面打包失败`、`打包失败` 应命中对应 chunk。
- 导入包含“模型配置”“Embedding 模型”“向量检索”的说明后，搜索这些中文词组应能稳定召回相关内容。

`KEYWORD` 模式可以在没有 Embedding 配置时验证中文 BM25 效果；配置并激活 Embedding 后，`HYBRID` 会同时利用中文关键词召回和语义召回。

### 代码搜索效果

代码块不会写入 SQLite 的增强文本，展示和 RAG 仍保留原文；Lucene 会额外派生代码检索字段，用于提升代码笔记召回。

可验证的查询示例：

- 类名或函数名：`ChatAgentRouter`、`KnowledgeBaseChatAgent`、`insertSession`。
- 拆分后的标识符：`chat agent router` 可以命中 `ChatAgentRouter`，`foo bar` 可以命中 `fooBar`，`snake case` 可以命中 `snake_case`。
- 异常和类型名：`DataIntegrityViolationException`、`IOException`、`ResultMap`。
- 路径片段：`src/main/resources`、`ChatSessionMapper.xml`、`application.yaml`。
- SQL / Shell / Java / JavaScript 代码块中的关键语句：`INSERT OR IGNORE`、`npm run build`、`public interface`。

这解决的是“代码笔记能被搜到”的召回问题，不是编译器级或 AST 级代码理解。它不会跨文件分析调用关系，也不会判断某段代码是否正确。

### 流程图搜索效果

Mermaid、PlantUML、sequence / flow / state 等流程图块会保留原文，同时派生图类型、节点文本和关系文本进入代码检索字段。

可验证的查询示例：

- Mermaid 节点名：`用户提问`、`路由 Agent`、`重建索引`。
- 图类型关键词：`sequenceDiagram`、`flowchart`、`stateDiagram`、`PlantUML`。
- 节点关系文本：流程图里写过的业务状态、步骤名称和边上的说明文字。

### HYBRID 排序效果

`HYBRID` 会让 BM25 和 Vector 各自先扩大候选集，再用加权 RRF 融合排序。直观效果是：同时在关键词和向量语义里排名靠前的 chunk 会更容易排到前面；只在其中一路命中的内容仍有机会进入结果，减少小 `topK` 下的漏召回。

检索测试页中：

- `score` 在 `HYBRID` 下表示 RRF 融合分数。
- `keywordScore` 是 Lucene BM25 原始分数。
- `vectorScore` 是向量召回原始分数。

### 验证方式

1. 打开“设置 -> 知识库 -> 知识库”，执行“重建全部索引”。
2. 打开“设置 -> 知识库 -> 检索测试”。
3. 先用 `KEYWORD` 搜中文词组、代码标识符和流程图节点，验证 BM25 与代码字段是否能命中。
4. 配置并激活 Embedding 模型后，再用 `HYBRID` 搜同一批 query，观察 RRF 分数和结果顺序。

如果旧版本导入时已经把代码块缩进清洗丢失，只重建 Lucene 无法恢复格式，需要重新导入原始文件。

## Public Interfaces

REST API 保持兼容：

- `POST /api/search`
- `GET /api/index/status`
- `POST /api/index/rebuild`
- 聊天 SSE 与 RAG sources 结构

新增配置项：

```yaml
app:
  search:
    bm25-k1: 1.2
    bm25-b: 0.65
    hybrid-candidate-multiplier: 8
    hybrid-min-candidates: 60
    rrf-k: 60
```

`COGNINOTE_BM25_WEIGHT` 和 `COGNINOTE_VECTOR_WEIGHT` 保留，但语义从 min-max 归一化加权变为 RRF 加权。

## Rebuild Rules

升级到本阶段后应全量重建 Lucene 索引。以下变化也需要重建索引：

- Analyzer 变化
- BM25 参数变化
- 派生索引文本策略变化
- Embedding 模型变化
- Embedding 维度变化

如果旧版本导入时已经把代码块缩进清洗丢失，只重建 Lucene 无法恢复格式，需要重新导入原始文件。

## Test Plan

- `mvn test`
- 中文正文查询能命中中文笔记。
- 代码标识符查询能命中 `ChatAgentRouter`、`snake_case`、`fooBar` 等代码 chunk。
- Mermaid / PlantUML / 流程图节点文本可被搜索命中。
- Java / SQL / Shell fenced code block 的缩进、换行和 fence 标记不被清洗破坏。
- HYBRID 使用扩大后的候选集和 RRF 排序。
- DashScope 分别使用 `document` / `query` text type。
- OpenAI-compatible 保持标准 Spring AI OpenAI `/embeddings` 调用。

## Assumptions

- 中文知识库是主要优化目标，但代码笔记和流程图笔记是一等场景。
- `text-embedding-v4 + 1024` 继续作为默认推荐配置。
- 本阶段不做 reranker、外部向量库、SQLite schema 变更或前端 API 破坏性改动。
