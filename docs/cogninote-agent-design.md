# CogniNote Agent 项目方案

## 1. 项目定位

CogniNote Agent 是一个以 Java + Vue 为核心技术栈的本地个人知识库智能体。

第一版目标不是做一个大而全的 Agent 平台，而是做一个能真正落地的本地知识库问答工具：

- 读取本地 Markdown、Word 文档和文本型 PDF
- 建立本地混合检索索引
- 通过用户配置的大模型进行问答
- 回答时提供来源引用和原文溯源
- 最终打包成 Windows 桌面应用，一键启动本地服务并在桌面窗口中使用

项目核心差异化：

- Java-first：主体使用 Spring Boot，而不是 Python/Node
- 本地优先：知识文件、SQLite 数据库、Lucene 索引和配置默认保存在本机
- 混合检索：优先用 Lucene 同时支持关键词检索和向量检索
- 模型开放：支持 DashScope / 百炼默认通道，也支持 OpenAI-compatible 自定义 Base URL；Ollama、LM Studio 等可通过兼容接口或后续专项适配接入

## 2. 第一版 MVP 范围

### 必做

- Spring Boot 后端
- Vue 3 前端
- 本地文件夹导入
- Markdown / TXT 文本解析
- Word 文档解析，第一版只支持 `.docx`
- 文本型 PDF 解析
- Lucene 本地混合检索
- 外部 Embedding API
- OpenAI-compatible 对话模型配置
- RAG 问答
- 答案引用来源
- 增量索引
- Tauri 桌面壳
- 桌面窗口加载本地页面

### 第一版不做

- Qdrant 适配
- OCR 图片 PDF 识别
- Obsidian 双链、标签、frontmatter 和关系图谱解析
- 老式 `.doc` 文档解析
- MCP Server
- ACP / Codex 接入
- Skill 市场
- 自动联网构建知识库
- 跨平台发布和系统托盘增强

这些能力可以作为后续版本演进，不进入 MVP。

## 3. 交付形态

第一版采用“Tauri 桌面壳托管本地 Web 应用”的方式。

```text
用户双击 CogniNote.exe
        ↓
Tauri 主进程启动
        ↓
选择本地端口并启动 Spring Boot 后端
        ↓
Spring Boot 加载 Vue 打包后的静态页面
        ↓
Tauri WebView 访问 http://127.0.0.1:{port}
```

Vue 不单独部署。执行 `npm run build` 后，将生成的静态文件放入 Spring Boot 的 `src/main/resources/static`，由后端统一托管。

推荐监听地址：

```text
127.0.0.1
```

不要监听 `0.0.0.0`，避免本地服务暴露到局域网。

端口建议自动探测，避免固定使用 `8080` 导致冲突。

## 4. 总体架构

```text
Vue 3 前端
  ├─ 对话页面
  ├─ 持久化会话列表
  ├─ AI Streaming Markdown Renderer
  ├─ SSE Chat Stream Parser
  └─ 设置页面
      ├─ 系统与主题
      ├─ 知识库管理
      └─ 模型配置

Spring Boot 后端
  ├─ Chat API / SSE Adapter
  ├─ Agent Execution Service
  ├─ AI Runtime (DashScope / OpenAI-compatible)
  ├─ SQLite Chat Memory
  ├─ Repository + MyBatis XML Mapper
  ├─ Spring AI Advisor (Memory / RAG)
  ├─ CogninoteDocumentRetriever
  ├─ Knowledge Context Provider
  ├─ KnowledgeStore 接口
  ├─ LuceneKnowledgeStore
  ├─ Document Ingestion Pipeline
  ├─ Embedding Gateway (读取 active Embedding 并通过 AI Runtime 调用)
  ├─ Metadata Service
  └─ App Launcher

本地数据目录
  ├─ SQLite 元数据、Chunk 文本、会话和消息
  ├─ Lucene 索引
  ├─ 配置文件
  └─ 日志文件
```

## 5. 后端模块设计

### 5.1 Document Ingestion Pipeline

负责将本地文件转成可检索的知识片段。

流程：

```text
选择文件夹
  ↓
扫描支持的文件
  ↓
解析文件内容
  ↓
文本清洗
  ↓
分块
  ↓
计算内容哈希
  ↓
调用 Embedding API
  ↓
写入 Lucene 索引
  ↓
写入 SQLite 元数据与 Chunk 文本
```

第一版支持：

- `.md`
- `.txt`
- `.docx`
- `.pdf`，仅支持有文本层的 PDF

第一版不支持：

- `.doc` 老式 Word 文档
- 扫描件或图片型 PDF
- HTML
- Obsidian 专用语法解析

说明：Obsidian 的普通笔记本质上仍是 Markdown 文件，第一版可以作为普通 `.md` 读取，但不解析 `[[双链]]`、标签、frontmatter 和关系图谱。

### 5.2 KnowledgeStore

统一知识库检索接口。

```java
public interface KnowledgeStore {
    void index(List<KnowledgeChunk> chunks);

    SearchResult search(SearchRequest request);

    void deleteByDocumentId(String documentId);

    KnowledgeStoreInfo info();
}
```

第一版只实现：

```text
LuceneKnowledgeStore
```

Lucene 中维护：

- 文档 ID
- Chunk ID
- 文件路径
- 标题
- Chunk 序号
- 内容哈希
- 关键词索引字段
- 向量字段
- 少量预览文本

注意：Lucene 是检索索引，不是业务数据库。不要把 Lucene 作为唯一数据源。

推荐分工：

- SQLite：保存文档元数据、Chunk 文本、模型配置、聊天会话、消息和应用设置
- Lucene：保存 BM25 派生字段、代码标识符字段、向量字段和检索必要字段
- 原始文件：仍保留在用户自己的目录里，应用不复制原文件

检索时先由 Lucene 返回 `chunk_id`，再用 `chunk_id` 到 SQLite 查询 `chunks.content`。第十三阶段后，知识库片段由 `CogninoteDocumentRetriever` 转成 Spring AI `Document`，再通过 `RetrievalAugmentationAdvisor` 注入模型调用链。

### 5.3 混合检索

检索方式：

- 关键词检索：Lucene BM25。正文使用 `SmartChineseAnalyzer`，代码/标识符字段使用 `StandardAnalyzer`，并从原始 chunk 派生类名、函数名、变量名、路径、异常名和流程图节点文本。
- 语义检索：读取 active `EMBEDDING` 配置，通过 Embedding 模型把用户问题转换为查询向量，再做向量相似度召回。
- 混合检索：BM25 和 Vector 各取 `max(topK * 8, 60)` 候选，再使用加权 RRF 融合排序。

默认权重：

```text
BM25: 0.45
Vector: 0.55
RRF K: 60
```

`SearchHitResponse.score` 在 `HYBRID` 下表示最终 RRF 分数；`keywordScore` 和 `vectorScore` 保留原始 BM25 / Vector 分数，主要用于调试和检索测试展示。修改 Analyzer、索引文本策略、Embedding 模型或维度后必须重建 Lucene 索引；如果旧 chunks 已经被旧清洗逻辑破坏缩进，需要重新导入原始文件才能恢复代码格式。

检索效果：

- 中文正文：搜索 `知识库重建索引`、`桌面打包失败`、`模型配置`、`向量检索` 这类中文短语时，BM25 不再完全依赖空格或英文 token，`KEYWORD` 模式即可验证中文 Analyzer 的召回效果。
- 代码笔记：搜索 `ChatAgentRouter`、`chat agent router`、`snake_case`、`snake case`、`fooBar`、`foo bar`、`DataIntegrityViolationException`、`ChatSessionMapper.xml` 等类名、拆分标识符、异常名和路径片段时，会同时命中代码/标识符派生字段。
- 流程图笔记：搜索 Mermaid / PlantUML 的图类型、节点名和边关系文本，例如 `flowchart`、`sequenceDiagram`、`用户提问`、`重建索引`，可以命中对应流程图 chunk。
- 混合排序：`HYBRID` 会让 BM25 和 Vector 各自扩大候选集后用 RRF 融合；同时被关键词和语义召回排在前面的 chunk 更容易靠前，只命中一路的 chunk 也仍有机会进入结果。

边界：代码搜索不是 AST 分析器，不会跨文件推导调用关系，也不会判断代码正确性。它解决的是代码笔记、流程图和技术文档“能被搜到”的召回问题；真正展示和注入 RAG 的内容仍然是 SQLite 中保存的原文 chunk。

### 5.4 Embedding Gateway

嵌入模型不放进 JVM。CogniNote 使用自己的 `EmbeddingGateway` 和 `AiEmbeddingRuntime` 隔离 provider 差异，默认推荐 DashScope `text-embedding-v4`。业务代码只区分“文档向量化”和“查询向量化”，不直接依赖 Alibaba 或 OpenAI 具体类。

第一版推荐默认接入：

- DashScope / 百炼 embedding：通过 Spring AI Alibaba `DashScopeEmbeddingOptions.textType` 分别传递 `document` 和 `query`。
- OpenAI-compatible embedding：继续走 Spring AI OpenAI runtime 的标准 `/embeddings` 请求；Spring AI OpenAI 的 embedding options 没有 `text_type` 字段，CogniNote 不发送非标准参数。
- 后续可扩展 Ollama、DeepSeek 或其它 provider，只需实现对应 `AiEmbeddingRuntime`。

统一接口：

```java
public interface EmbeddingGateway {
    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String query);
}
```

配置项：

```yaml
spring:
  ai:
    model:
      chat: none
      embedding: dashscope
      embedding.text: dashscope
    dashscope:
      api-key: ${COGNINOTE_DASHSCOPE_API_KEY}
      agent:
        enabled: false
      chat:
        enabled: false
      embedding:
        options:
          model: text-embedding-v4

app:
  embedding:
    dimensions: 1024
```

未配置 API Key 时，关键词检索仍应可用；向量索引和混合检索需要明确提示 Embedding 未启用。Spring AI Alibaba 和 Spring AI OpenAI 的若干自动配置默认会尝试创建模型 Bean，因此默认把 `spring.ai.model.embedding.text`、`spring.ai.model.chat`、`spring.ai.model.image`、`spring.ai.model.moderation` 等未使用模块设为 `none`。模型实例由 SQLite 中的 active 配置在 AI Runtime 层动态创建。

### 5.5 AI Runtime 与 Agent 执行层

所有大模型调用必须统一收敛到模型运行时层。对话层不直接绑定某个厂商 SDK，也不直接知道 OpenAI-compatible 的 HTTP 细节或 DashScope 的 endpoint 选择规则。

当前实现状态：

- `DASHSCOPE` 使用 Spring AI Alibaba 原生 `DashScopeChatModel` / `DashScopeEmbeddingModel`。
- `OPENAI_COMPATIBLE` 使用 Spring AI OpenAI 官方模型实现，保留用户配置的 `Base URL + /chat/completions` 与 `Base URL + /embeddings` 语义。
- `AiRuntimeFactory` 根据 active `ModelConfig` 创建和缓存 Chat / Embedding runtime。
- `DashScopeRuntime` 继续封装 Spring AI Alibaba，并保留 DashScope 默认百炼地址和多模态 endpoint 判断。
- `OpenAiCompatibleRuntime` 使用 Spring AI OpenAI 官方模型实现，读取用户自定义 Base URL、API Key、模型 ID 和模型参数。
- 旧 `OpenAiCompatibleClient` / `OpenAiCompatibleEmbeddingClient` 已删除，避免长期维护两套 OpenAI-compatible 调用路径。
- `AgentExecutionService` 通过 `ChatAgentRouter` 路由到具体 Agent，Controller 只负责 SSE 适配。
- `GENERAL_CHAT` 普通对话 Agent 只挂会话记忆，不检索知识库；`KNOWLEDGE_BASE` 知识库 Agent 挂会话记忆和 RAG Advisor。
- `AbstractChatAgent` 抽取 active Chat 模型、会话更新、消息落库、流式保存、停止保存、错误保存和日志，具体 Agent 只覆盖 Prompt、Advisor 和知识库上下文。
- `SpringAiChatRuntime` 必须保留 Spring AI 流式返回中的空格和换行 chunk；这些空白可能是 Markdown 标题、列表、缩进和代码块语法的一部分，不能用 `isBlank()` 过滤。
- `SpringAiChatRuntime` 必须读取 Spring AI `ChatResponse` 元数据里的 `finishReason`。`length`、`max_tokens`、`max_completion_tokens` 和 `content_filter` 都表示本轮回答未完整完成，应抛出业务异常并保存为错误状态，不能把半截内容当作正常 `DONE`。
- `ChatSseEventMapper` 负责把内部 `AgentEvent` 映射为 SSE，并通过 `requestId` 注册显式取消能力。

这次重构的边界是“整理调用层”，不是“把 RAG 强行改成 Spring AI VectorStore”。当前 Lucene + SQLite 的检索、来源展示和降级逻辑继续由 CogniNote 自己掌控。

第十六阶段后，SQLite 访问统一收敛到 Repository + MyBatis XML Mapper。Service 和 Controller 仍只依赖 Repository 或业务服务，不能直接依赖 MyBatis Mapper；Mapper XML 负责承载文档、知识库目录、模型配置、聊天会话和 schema 初始化 SQL。

用户在前端配置：

- Provider 类型
- Base URL（仅 OpenAI-compatible 可自定义；DashScope 使用默认百炼地址）
- API Key
- 模型 ID
- 配置类型：`CHAT` 或 `EMBEDDING`
- Chat 上下文窗口：默认 `128000` tokens，前端显示 `128K`，仅用于本地历史裁剪、压缩和上下文占用展示

后端接口：

```text
GET    /api/model-configs?role=CHAT|EMBEDDING
GET    /api/model-configs/active
POST   /api/model-configs
PUT    /api/model-configs/{id}
DELETE /api/model-configs/{id}
POST   /api/model-configs/{id}/activate
POST   /api/model-configs/test
POST   /api/model-configs/models

GET    /api/model-configs/settings?role=CHAT|EMBEDDING
POST   /api/model-configs/settings/configs
PUT    /api/model-configs/settings/configs/{id}
DELETE /api/model-configs/settings/configs/{id}
POST   /api/model-configs/settings/configs/{id}/activate
```

第五阶段先把模型配置页做扎实：用户选择 `DASHSCOPE` 时使用默认百炼通道，配置页展示 `https://dashscope.aliyuncs.com/api/v1`；选择 `OPENAI_COMPATIBLE` 时输入自定义 Base URL，后端按 `Base URL + /models`、`Base URL + /chat/completions`、`Base URL + /embeddings` 调用通用接口。

第八阶段把单个 active 模型配置拆成多配置中心：`CHAT` 和 `EMBEDDING` 独立维护、独立激活。RAG 回答读取 active Chat 配置；文档向量化、向量检索和混合检索读取 active Embedding 配置。旧 `/api/model-config` 只作为过渡兼容接口保留。

第 22 阶段为 Chat 配置新增 `contextWindowTokens`。默认 Chat 配置为 `128000`，Embedding 配置保持 `null`；保存时校验范围为 `1024` 到 `2000000`。该字段不会作为通用上下文参数发送给模型 API，只用于 `ConversationMemorySnapshotService` 的历史预算、压缩策略和前端上下文占用展示。

第 23 阶段新增全局聊天设置 `queryContextualizerMode`，取值为 `AUTO`、`ALWAYS`、`OFF`，保存在 SQLite `app_settings`。该设置在“设置 -> 模型 -> 对话模型”中展示，但不属于单个 `model_configs` 记录；它只控制知识库模式下是否调用追问补全 Agent。

模型设置页使用 settings 快照接口作为页面事实来源：顶部 Active 卡片、左侧配置列表和右侧编辑表单由同一份 `ModelConfigSettingsResponse` 驱动。前端 `model-config` store 只保留一个当前编辑 `form`，不要在组件内再复制第二份表单状态；否则容易出现“列表和 Active 有数据，但右侧表单没有回显”的状态分叉。设置页切到“模型”时默认加载 `CHAT` 快照，点击 “Embedding 模型” 时再加载 `EMBEDDING` 快照。模型页不显示整块加载遮罩，避免页签切换时闪烁刺眼。

API Key 第四、五阶段仍以开发态明文保存到 SQLite；本地加密或 Windows 凭据管理放到安全加固阶段，不能在最终交付版本继续明文保存。

### 5.6 RAG 问答流程

```text
用户提问
  ↓
写入/确认 SQLite 会话和 user 消息
  ↓
ConversationMemorySnapshotService 读取会话摘要 + token 预算内最近原文消息
  ↓
ChatAgentRouter 按 useKnowledgeBase 选择 Agent
  ↓
useKnowledgeBase=false
  └─ GENERAL_CHAT：普通对话 prompt + CogninoteMemoryAdvisor
      ↓
      调用 active CHAT 模型
      ↓
      流式返回答案
      ↓
      完整回答写入 SQLite 为 DONE；异常时非空片段写入 ERROR；用户停止时非空片段写入 STOPPED

useKnowledgeBase=true
  └─ KNOWLEDGE_BASE：RAG prompt + CogninoteMemoryAdvisor
      ↓
      按 queryContextualizerMode 决定是否调用 QueryContextualizerAgent
      ↓
      读取 active EMBEDDING 配置
      ↓
      Embedding 模型基于检索 query 生成查询向量
      ↓
      Lucene 混合检索
      ↓
      取 Top K 片段
      ↓
      通过 Spring AI RetrievalAugmentationAdvisor 注入知识库片段
      ↓
      调用 active CHAT 模型
      ↓
      流式返回答案
      ↓
      完整回答写入 SQLite 为 DONE；异常时非空片段写入 ERROR；用户停止时非空片段写入 STOPPED，并附带本轮引用来源
```

知识库模式回答必须包含引用来源，避免变成不可验证的聊天工具。普通对话模式不返回引用来源，`retrievalMode=null` 且 `sources=[]`。

第十三阶段后，RAG 不再把 `{context}` 手动拼进 user prompt。`CogninoteDocumentRetriever` 挂在 Spring AI `RetrievalAugmentationAdvisor` 上，内部仍复用现有 `KnowledgeStore.search()`、Embedding 降级和 SQLite chunk 回读逻辑。这样既保留 CogniNote 的 Lucene + SQLite 检索能力，也把知识库增强放回 Spring AI Advisor 调用链。

第 21 阶段后，知识库模式具备内部 `QueryContextualizerAgent`。它复用 active Chat 模型，但使用独立 JSON Prompt 判断当前问题是否是省略式追问；如果需要，会把历史主题补进 `retrievalQuery`，例如把“给出代码示例”补成“红黑树是什么？在 Java 中哪里用到了这个结构？ 给出代码示例”。用户原始消息仍按原文写入 SQLite，最终回答也面向用户原始问题；`retrievalQuery` 只用于知识库检索和 RAG 边界说明。补全 Agent 返回非法 JSON、字段缺失、空 query、过长 query 或调用异常时，会回退原问题检索，不阻断主对话。

第 23 阶段后，追问补全不再默认每轮调用模型，而是由 `queryContextualizerMode` 控制：`AUTO` 为默认值，通过本地打分判断短句、省略、指代、延续动作和完整问题反向信号，必要时才调用补全 Agent；`ALWAYS` 保留第 21 阶段每轮判断行为；`OFF` 完全关闭补全。AUTO 模式下，无历史时不调用补全 Agent，完整独立问题直接检索；如果原问题检索无来源且存在历史，会允许一次弱检索补全重试。补全 Prompt 输入包含“会话摘要 + 最近 N 条原文消息 + 当前问题”，压缩会话不会只看最近消息。

`CogninoteDocumentRetriever` 转换 Spring AI `Document` 时必须保证 metadata 不包含 `null`。`heading`、`pageNumber` 等来源字段允许在 SQLite/Lucene 中为空，但传给 Spring AI 时要省略缺失字段；否则 Spring AI 1.1.x 会在 `Document` 构建阶段抛出 `metadata cannot have null values`，导致 RAG Advisor 流式链路中断。

聊天记忆采用分层策略：SQLite 保存全量消息；模型输入只注入会话摘要和 token 预算内最近原文消息，默认至少保留最近 8 条原文消息，避免长会话简单截断成固定最近 20 条。第 22 阶段后，历史预算优先来自 active Chat 配置的 `contextWindowTokens`，默认 `128000`，历史消息最多使用约 80% 窗口；`max-history-tokens=6000` 仅作为旧配置兼容兜底。压缩触发优先看 token 是否超预算，`summarize-after-messages=200` 只作为消息数兜底。第十八阶段后，每条消息记录 `agent_type`，同一会话在普通对话和知识库模式之间切换时，跨 Agent 的 assistant 历史只作为带标签参考注入，不能把上一种 Agent 的系统规则、拒答规则或引用规则带到当前 Agent。旧 SQLite 消息读取时会兼容推断：assistant 有 `retrieval_mode` 视为 `KNOWLEDGE_BASE`，否则视为 `GENERAL_CHAT`；旧 user 消息按中性历史处理。

Token 估算优先使用本地 JTokkit。DashScope/Qwen 默认采用 `o200k_base`，OpenAI-compatible 优先按模型名匹配 tokenizer，识别不到时使用 `cl100k_base`；单条 chat message 会追加 framing overhead，避免低估上下文。聊天页通过 `ChatContextUsageResponse` 展示当前会话占用，已压缩会话按“摘要 + 最近原文消息”重新估算。`ChatSessionResponse`、SSE `meta` 和 SSE `done` 都携带同一口径的 `contextUsage`。

### 5.7 流式输出与 Markdown 合同

AI 回答的 Markdown 质量不只取决于前端渲染器，也取决于后端流式传输是否保留模型原文。第十二阶段后，流式输出遵循以下合同：

- SSE 事件顺序保持 `meta -> delta -> done/error`；`meta` 包含 `requestId`、`conversationId`、实际检索模式、引用来源和当前上下文占用 `contextUsage`。
- `delta.text` 是模型原始增量，可能只包含一个空格、换行或缩进。后端 runtime、SSE mapper 和前端 parser 都不能对它做 `trim()`、`trimStart()` 或 `isBlank()` 过滤。
- 前端手写 SSE parser 只能移除 `data:` 后一个协议分隔空格，不能移除内容本身的前导空白。
- Prompt 在 `application.yaml` 中要求模型输出标准 Markdown：标题符号后带空格、列表符号后带空格、代码块使用 fenced code block、禁止原始 HTML。
- 前端必须收到 `done` 或 `error` 终止事件才认为本轮 SSE 流完整结束；连接提前结束但没有终止事件时，本轮回答应显示为“未完成”。
- `POST /api/chat/stream/{requestId}/cancel` 只表示用户显式停止。普通刷新、切页或 SSE 连接断开时，后端仍消费模型流到结束，为第十三阶段保存完整 assistant 消息预留空间。
- SSE 响应一旦进入 `text/event-stream`，全局异常处理不能再写 JSON `ApiResponse`；业务错误尽量映射为 SSE `error` 事件，容器级异常只关闭响应。

第十三阶段后，普通刷新、切页或 SSE 连接断开时，后端完成生成后会把完整 assistant 消息写入 `chat_messages`。只有用户显式点击停止并调用取消接口时，才中断模型订阅并把已生成片段保存为 `STOPPED`。第十八阶段后的流式错误处理规则如下：

- 模型正常完成且 assistant 内容非空：保存 `DONE`。
- 用户显式停止且 assistant 内容非空：保存 `STOPPED`。
- 模型截断、Provider 异常或后端调用异常，且 assistant 内容非空：保存 `ERROR`。
- 模型还未返回任何 assistant 内容就失败：不保存 assistant 消息，只保留本轮 user 消息和前端错误提示。
- 前端收到非用户主动停止的错误后，会先保留当前错误气泡，再按本轮 `requestId` 延迟刷新会话详情；只有后端已经写入同一个 `requestId` 的 assistant 消息时，才用 SQLite 事实来源覆盖本地临时气泡。

## 6. 前端页面设计

### 6.1 对话页

对话页是默认入口。第十三阶段后，前端会话改为后端 SQLite 事实来源：

- 左侧 sidebar 显示应用标识、新建对话、持久化会话列表和设置入口。
- 会话列表按更新时间倒序从 `GET /api/chat/sessions` 加载。
- 支持新建、切换、重命名、删除和清空当前会话消息。
- 主区域显示当前会话的消息流，支持 user、assistant、error、loading、stopped 等状态。
- 刷新页面后通过 `GET /api/chat/sessions/{conversationId}` 恢复历史消息、引用来源、检索模式和停止/错误状态。
- Assistant 消息使用 AI 流式 Markdown 渲染器；外部模型输出的原始 HTML 被转义，避免模型内容直接注入页面。
- Assistant 消息的流式拼接必须保留空格、换行和缩进；这些字符直接影响 Markdown 标题、列表、表格和代码块能否正确渲染。
- Assistant 消息支持 ` ```mermaid ` fenced code block 渲染；Mermaid 作为 `markstream-vue` 的可选 peer 显式启用，图表只在聊天 Markdown 容器内使用局部样式约束宽度和主题。
- Assistant 错误状态仍使用 Markdown 渲染，并在消息标签显示“未完成”；这表示后端或前端已经确认本轮流没有正常 `done`。
- 引用来源跟随 assistant 消息展示，默认折叠，用户点击后展开文件名、路径、标题、页码、chunk 和预览。
- 输入区右侧放置对话设置按钮和发送/停止图标按钮，避免设置项挤压文本框。
- 对话设置通过右侧浮层展开，包含“使用知识库”、关键词/向量/混合检索模式和 Top K。
- 对话设置浮层由独立 `chat-settings-popover.vue` 维护，只通过 `props -> emit -> chat store setter` 更新状态；不要让原生 checkbox / number input 直接 `v-model` 到 Pinia store，避免浮层摘要、表单控件和当前会话设置分叉。
- “使用知识库”在界面上使用受控 switch 表达。关闭后走纯模型对话，只挂会话记忆 Advisor，不挂 RAG Advisor。
- `conversationId` 只作为 SSE 协议内部字段，不在前端界面显示。

### 6.2 设置页

设置页使用独立全屏布局，点击左侧 sidebar 底部“设置”后不再显示对话栏；页面顶部提供“返回对话”。非聊天能力统一归拢到设置页内：

- 系统：显示应用状态、数据目录、索引目录，并提供深色/夜间和日间主题切换。
- 知识库：复用知识库管理能力。
- 模型：复用模型配置能力。

主题偏好保存在前端 `localStorage` 的 `cogninote-theme` 中，并通过 `html.theme-dark` / `html.theme-light` 类控制全局样式。默认主题为深色/夜间。

第九阶段会专门收敛运行截图暴露出的视觉问题：字体发虚、深色主题黑白反差过大、浅色主题青绿色过重、模型配置卡片文字压迫和长文本难读。前端样式需要通过统一 token 管理背景、表面、边框、正文、辅助文本、accent、危险色、阴影和圆角，避免每个页面单独调色导致视觉割裂。

### 6.3 知识库管理

核心功能：

- 通过桌面文件夹选择器或手动路径导入本地目录
- SQLite 保存知识库目录记录，文档按目录分组展示
- 文件夹支持展开/收起，查看该目录下的文档、chunks 和索引状态
- 文件夹支持启用/停用；停用后清理 Lucene 条目但保留 SQLite 解析结果
- 文件夹支持局部重建，只重新扫描和重建该目录索引
- 删除文件夹只删除应用内目录、文档、chunks 和索引记录，不删除用户本机原始文件
- 旧版本散落文档显示在“未归属文档”区域，不自动猜测归属目录

### 6.4 模型配置

核心功能：

- `CHAT` 和 `EMBEDDING` 两类配置独立维护
- 每类配置支持多条保存、编辑、删除和激活
- 选择阿里百炼或 OpenAI-compatible Provider
- 为 OpenAI-compatible 输入 Base URL；DashScope 使用默认地址
- 输入、显示和复制 API Key
- 测试连接
- 自动拉取模型列表
- 为对话模型配置 Temperature 和默认 Top K
- 为 Embedding 模型配置维度

模型配置页的数据流：

- `settings-view` 切到“模型”页签时调用 `enterModelSettings()`，默认读取 `CHAT` 快照。
- `model-config-view` 挂载时调用 `initializeEditor()` 兜底，避免直接刷新 `/model-config` 时没有数据。
- 点击 `对话模型` / `Embedding 模型` 页签时分别请求对应 role 的 settings 快照。
- 点击左侧配置时只把该配置复制进当前 `form`，不请求后端。
- 保存、启用、删除后用后端返回的 settings 快照刷新 Active 卡片、配置列表和右侧表单。

### 6.5 前端工程结构

当前前端按 Vue Router + Pinia + API client 分层：

```text
cogniNote-agent-front/src/
  ├─ api/                  # 统一 JSON API 解包和 SSE chat stream parser
  ├─ components/           # 应用壳、列表、来源、AI Markdown 渲染等复用组件
  ├─ router/               # /chat、/settings、/knowledge、/model-config
  ├─ stores/               # system、documents、search、modelConfig、chat、theme
  ├─ styles/               # base、layout、controls、markdown、theme 等样式模块
  └─ views/                # chat、settings、knowledge、model-config 页面
```

`/knowledge` 和 `/model-config` 仍保留为可刷新路由，但主界面从设置页进入对应能力，避免桌面应用出现多个平级工作台入口。

## 7. 本地数据目录

推荐默认目录：

```text
%APPDATA%/CogniNote/
```

结构：

```text
CogniNote/
  ├─ config/
  │   └─ app-settings.json
  ├─ data/
  │   └─ cogninote.db
  ├─ index/
  │   └─ lucene/
  └─ logs/
      ├─ app.log
      └─ desktop-backend.log
```

不要默认把数据写在安装目录下。Windows 安装目录可能没有写权限。

`app.log` 是 Spring Boot 业务日志，默认路径可通过 `COGNINOTE_LOG_FILE` 覆盖；`desktop-backend.log` 是 Tauri 启动后端进程时收集的 stdout/stderr。定位桌面启动问题先看 `desktop-backend.log`，定位接口、索引、模型连接和 RAG 问题先看 `app.log`。

## 8. 数据存储设计

第一版采用 SQLite + Lucene 的组合。

```text
SQLite = 业务事实来源
Lucene = 可重建的搜索索引
原始文件 = 用户自己的文件，不复制
```

不要只用 SQLite，因为 SQLite 不适合承担高质量全文检索、BM25 和向量召回。

也不要只用 Lucene，因为 Lucene 索引更适合搜索，不适合保存模型配置、文档状态、任务状态等业务数据。

第十六阶段后，SQLite 的业务读写、启动建表/补列/轻量迁移和测试清库都通过 MyBatis XML Mapper 执行。这里的“统一 MyBatis”不表示移除 JDBC 基础设施：MyBatis、`sqlite-jdbc`、`DataSource` 和 Spring 事务仍然运行在 JDBC 之上，只是不再让业务代码直接散落 `JdbcTemplate` SQL。

### 8.1 SQLite 存什么

SQLite 保存结构化业务数据和 RAG 需要回读的 Chunk 内容。

核心表：

```sql
CREATE TABLE documents (
    id TEXT PRIMARY KEY,
    knowledge_folder_id TEXT,
    source_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size INTEGER,
    last_modified INTEGER,
    content_hash TEXT,
    status TEXT NOT NULL,
    indexed_at INTEGER,
    created_at INTEGER,
    updated_at INTEGER
);

CREATE TABLE knowledge_folders (
    id TEXT PRIMARY KEY,
    folder_path TEXT NOT NULL,
    display_name TEXT NOT NULL,
    recursive INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 1,
    last_ingested_at INTEGER,
    last_indexed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE chunks (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_number INTEGER,
    heading TEXT,
    token_count INTEGER,
    created_at INTEGER,
    FOREIGN KEY (document_id) REFERENCES documents(id)
);

CREATE TABLE model_configs (
    id TEXT PRIMARY KEY,
    role TEXT NOT NULL,
    provider TEXT NOT NULL,
    display_name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT,
    model_name TEXT NOT NULL,
    embedding_dimensions INTEGER,
    temperature REAL,
    default_top_k INTEGER,
    context_window_tokens INTEGER,
    is_active INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE chat_sessions (
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

CREATE TABLE chat_messages (
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

`knowledge_folders` 是第十阶段新增的知识库目录表。`documents.knowledge_folder_id` 只记录明确通过目录导入产生的归属；历史散落文档不自动猜测目录，继续作为未归属文档保留。

`model_configs.context_window_tokens` 是第 22 阶段新增的 Chat 上下文窗口字段。默认 Chat 配置为 `128000`，Embedding 配置为 `null`；旧库启动时由 schema 初始化自动补列。

`chat_sessions` 与 `chat_messages` 是第十三阶段新增的聊天记忆事实来源。SQLite 保存全量消息；`summary` 和 `summary_message_sequence` 只描述已被摘要覆盖的历史范围，模型输入仍由 `ConversationMemorySnapshotService` 按 active Chat 上下文窗口选择“摘要 + 最近原文消息”，不写死固定条数。删除会话是物理删除，会同时移除会话行和对应消息；第十八阶段新增 `chat_messages.agent_type`，用于标记消息所属 Agent，并在普通对话和知识库模式切换时隔离跨 Agent 记忆污染。

`app_settings` 是第 23 阶段新增的全局应用设置表，当前用于保存 `chat.query-contextualizer.mode`。它的优先级高于 `COGNINOTE_QUERY_CONTEXTUALIZER_MODE` 和旧的 `COGNINOTE_QUERY_CONTEXTUALIZER_ENABLED=false` 兼容开关。

`chunks.content` 会额外占用一份解析后的文本空间，这是有意设计。

原因：

- `.docx` 和 `.pdf` 的原文偏移难以稳定回读
- 每次问答重新解析原文件性能差
- Lucene 索引损坏后，可以用 SQLite 的 Chunk 文本重建索引

### 8.2 Lucene 存什么

Lucene 保存检索需要的数据：

```text
chunk_id
document_id
file_name
source_path
heading
page_number
content_for_bm25
content_for_code
embedding_vector
preview_text
```

`content_for_bm25` 保存从文件名、标题和正文派生出的中文友好索引文本；`content_for_code` 保存代码块、流程图、类名、函数名、变量名、路径、异常名和拆分后的 camelCase / snake_case / kebab-case token。两者都只服务 Lucene 检索，不作为展示数据来源。真正展示和喂给模型的文本，以 SQLite 中的 `chunks.content` 为准。

### 8.3 检索回读流程

```text
用户提问
  ↓
读取 active EMBEDDING 配置
  ↓
Embedding 模型生成查询向量
  ↓
Lucene 执行 BM25 + 向量混合检索
  ↓
返回 chunk_id 列表
  ↓
SQLite 查询 chunks.content 与 documents 元数据
  ↓
转换为 Spring AI Document，并保留非空的 chunkId/documentId/fileName/sourcePath/heading/pageNumber/score 等 metadata
  ↓
RetrievalAugmentationAdvisor 注入知识库片段
  ↓
CogninoteMemoryAdvisor 注入会话摘要和最近原文消息
  ↓
调用 active CHAT 模型
  ↓
返回答案和引用来源
```

### 8.4 存储取舍

第一版不复制用户原始文件，只保存解析后的 Chunk 文本和索引。

这意味着磁盘占用包括：

- 用户原始文件，用户自己已有
- SQLite 中的解析文本与元数据
- Lucene 索引和向量

这是本地 RAG 工具合理的空间换性能方案。

## 9. 打包方案

第六阶段已完成 Windows 桌面打包。第十四阶段新增 macOS Apple Silicon 独立打包链路。第十五阶段把双平台桌面包升级为 `0.1.0` CI 双模式链路：无证书时产出 unsigned 测试包；配置完整证书后，Windows 使用 Authenticode 签名和时间戳，macOS 使用 Developer ID 签名、公证和 staple。两个平台的 Tauri 配置、脚本和后端 app-image 输出目录分开维护，不把平台差异塞进同一份 bundle 配置或总脚本。

Windows 打包链路：

```text
Vue build
  ↓
复制 dist 到 Spring Boot static
  ↓
Maven 打 Fat Jar
  ↓
jpackage 生成后端 app-image
  ↓
Tauri 打包桌面壳和后端资源目录
  ↓
生成 Windows 安装包
  ↓
CI 无证书时上传 unsigned 测试包；有证书时签名 release exe 和 NSIS installer
```

macOS 打包链路：

```text
Vue build
  ↓
复制 dist 到 Spring Boot static
  ↓
Maven 打 Fat Jar
  ↓
jpackage 生成 macOS 后端 CogniNoteBackend.app
  ↓
Tauri 使用 tauri.macos.conf.json 打包桌面壳和后端资源目录
  ↓
生成 macOS .app / .dmg
  ↓
CI 无证书时上传 unsigned 测试包；有证书时公证并 staple .app / .dmg
```

启动逻辑：

1. Tauri 主进程启动
2. Tauri 在 `18080-18120` 中选择可用端口
3. Tauri 设置 `COGNINOTE_PORT` 并启动 `jpackage` 后端 app-image
4. Spring Boot 只监听 `127.0.0.1`
5. Tauri 等待 `/api/system/status` 健康检查通过
6. Tauri WebView 加载 `http://127.0.0.1:{port}/`

注意：`jpackage --type app-image` 的产物依赖完整 app-image 目录，不能只复制启动器。Windows 后端资源目录是 `target/desktop/backend/CogniNoteBackend/`，启动器是 `CogniNoteBackend.exe`；macOS 后端资源目录是 `target/desktop-macos/backend/CogniNoteBackend.app/`，启动器是 `Contents/MacOS/CogniNoteBackend`。

桌面构建和运行脚本统一放在项目根目录 `scripts/` 下。Windows 使用 `.ps1`，macOS 使用 `.sh`。具体命令、执行策略处理、产物路径和常见故障排查见 `docs/desktop-build-guide.md`。

后端 Jar 使用稳定文件名 `target/cogninote-agent-design.jar`，避免分发版本升级后桌面脚本仍引用旧 `SNAPSHOT` 文件名。GitHub Actions 中 Windows 和 macOS workflow 仍然手动触发；证书材料只来自 Secrets，不进入仓库。未配置证书时 CI 仍会产出明确标记为 `unsigned` 的测试 artifacts。

macOS 桌面版运行时显式注入：

```text
COGNINOTE_DATA_DIR=~/Library/Application Support/CogniNote
COGNINOTE_LOG_FILE=~/Library/Application Support/CogniNote/logs/app.log
```

后续可增加系统托盘能力：

- 打开主界面
- 查看服务状态
- 退出应用

## 10. API

```text
GET    /api/system/status

GET    /api/documents
POST   /api/documents/ingest
DELETE /api/documents/{id}

GET    /api/knowledge-folders
POST   /api/knowledge-folders/import
POST   /api/knowledge-folders/{id}/rebuild
PATCH  /api/knowledge-folders/{id}/enabled
DELETE /api/knowledge-folders/{id}

GET    /api/index/status
POST   /api/index/rebuild
POST   /api/search

GET    /api/model-configs?role=CHAT|EMBEDDING
GET    /api/model-configs/active
POST   /api/model-configs
PUT    /api/model-configs/{id}
DELETE /api/model-configs/{id}
POST   /api/model-configs/{id}/activate
POST   /api/model-configs/test
POST   /api/model-configs/models

GET    /api/model-configs/settings?role=CHAT|EMBEDDING
POST   /api/model-configs/settings/configs
PUT    /api/model-configs/settings/configs/{id}
DELETE /api/model-configs/settings/configs/{id}
POST   /api/model-configs/settings/configs/{id}/activate

GET    /api/chat/sessions
POST   /api/chat/sessions
GET    /api/chat/sessions/{conversationId}
PATCH  /api/chat/sessions/{conversationId}
DELETE /api/chat/sessions/{conversationId}
DELETE /api/chat/sessions/{conversationId}/messages
GET    /api/chat/settings
PUT    /api/chat/settings

POST   /api/chat/stream
POST   /api/chat/stream/{requestId}/cancel
```

普通 JSON API 统一返回 `ApiResponse<T>`。`POST /api/chat/stream` 使用 SSE 流式返回，不做 JSON 响应包装；`POST /api/chat/stream/{requestId}/cancel` 和 `/api/chat/sessions...` 都是普通 JSON API；`DELETE /api/documents/{id}`、`PATCH /api/knowledge-folders/{id}/enabled` 和 `DELETE /api/knowledge-folders/{id}` 成功时返回 `204 No Content`。

`GET /api/chat/settings` 和 `PUT /api/chat/settings` 用于读取和保存全局聊天设置。当前请求/响应字段为 `queryContextualizerMode`，合法值为 `AUTO`、`ALWAYS`、`OFF`；它只影响知识库检索 query 的补全策略，不改变用户原文和普通对话。

## 11. 开发里程碑

### Milestone 1：基础工程闭环

- Spring Boot 项目初始化
- Vue 项目初始化
- 前后端联调
- Vue 静态文件由 Spring Boot 托管
- 本地数据目录初始化

### Milestone 2：文档摄入

- 支持选择本地目录
- 支持 Markdown / TXT 解析
- 支持 `.docx` 解析
- 支持文本型 PDF 解析
- 文本分块
- SQLite 保存文档元数据和 Chunk 文本

### Milestone 3：Lucene 检索

- 建立 BM25 索引
- 接入 Embedding API
- 建立向量索引
- 实现混合检索
- 支持增量索引

### Milestone 4：RAG 对话

- 模型配置页
- 早期 LLM Gateway
- RAG Prompt 构造
- SSE 流式输出
- 引用来源展示

### Milestone 5：模型配置增强

- Provider 选择：DashScope 默认地址或 OpenAI-compatible 自定义 Base URL
- 自动获取模型列表
- 选择默认 Chat 模型
- 选择默认 Embedding 模型
- 模型配置保存、测试连接和前端可用性增强

### Milestone 6：本地交付

- Tauri 2 桌面壳
- jpackage 生成后端 app-image
- Tauri 启动和关闭 Spring Boot 后端进程
- 桌面窗口加载 Spring Boot 托管页面
- Windows 安装包

### Milestone 7：对话式前端

- 左侧会话列表与主对话流
- 设置页全屏化，归拢系统、知识库和模型配置
- Assistant Markdown 渲染
- 引用来源折叠/展开
- 对话设置浮层和发送/停止图标按钮
- 对话设置受控组件，保持知识库开关、检索模式和 Top K 与当前会话同步
- 深色/夜间与日间主题切换

### Milestone 8：多模型配置

- 对话模型和 Embedding 模型独立维护
- 支持每类模型新建、编辑、删除、激活和测试
- 旧单行 `model_config` 自动迁移到 `model_configs`
- RAG 使用 active Chat，Embedding 网关使用 active Embedding
- 设置页展示 active Chat / active Embedding

### Milestone 9：UI 视觉可读性与主题系统修正

- 收敛深色/夜间和日间主题 token
- 修正字体发虚、字号过大和过度加粗问题
- 降低深色主题黑白强反差，统一卡片、输入框、按钮和状态标签层级
- 优化模型配置、系统设置、知识库和聊天页的文字换行与信息密度
- 重构对话设置浮层视觉和状态绑定，避免开关、Top K 输入与摘要状态不一致
- 通过截图验收主要页面在日间/夜间主题下的可读性

### Milestone 10：知识库目录管理与局部索引重建

- 新增 `knowledge_folders` 表，保存导入目录、递归扫描和启用状态
- 文档按知识库目录分组展示，保留未归属文档区域
- 支持目录导入、启用/停用、删除和单目录重建
- 停用目录立即从搜索/RAG 中消失，但保留 SQLite 解析数据
- 删除目录不删除用户本机原始文件
- 桌面环境接入系统文件夹选择器，浏览器开发态保留手动路径输入

### Milestone 11：智能体模型运行时重构

- 新增统一 AI Runtime，收敛 DashScope 与 OpenAI-compatible 调用边界
- 新增 Agent 执行层，拆分 Controller、RAG、Prompt、模型流职责
- 抽离 Knowledge Context Provider，保留 Lucene + SQLite 来源展示和检索降级能力
- OpenAI-compatible 迁移到 Spring AI OpenAI 官方模型实现，并保留用户自定义 Base URL
- 清出模型运行时和 Agent 编排边界，为第十三阶段 ChatMemory 与 Advisor 接入降低耦合

### Milestone 12：AI 流式 Markdown 渲染重构

- 使用 `markstream-vue` 替换聊天页基础 `markdown-it` 渲染器
- Assistant 消息支持更稳定的流式 Markdown、表格、任务列表、代码块和 Mermaid 流程图渲染
- 原始 HTML 继续转义，不开放模型 HTML 注入
- 渲染器按需加载，避免设置页和空对话首屏被重型 Markdown 依赖拖慢
- 后端 Spring AI 流式 runtime 保留空格和换行 chunk，避免 Markdown 语法在传输中被破坏
- 前端 SSE parser 保留 `delta.text` 内容前导空白，只移除 `data:` 后一个协议分隔空格
- 新增显式取消接口，用户停止时取消模型流；普通连接断开不取消生成任务
- SSE 错误兜底不再尝试在 `text/event-stream` 响应上写 JSON `ApiResponse`

### Milestone 13：SQLite 聊天记忆与 Spring AI RAG Advisor 重构

- 新增 `chat_sessions` 与 `chat_messages`，SQLite 保存全量会话历史、消息状态、requestId、检索模式和 sources JSON
- 新增会话 CRUD、消息查询和清空消息 API，前端会话列表改为后端事实来源
- 模型输入采用会话摘要 + token 预算内最近原文消息，默认至少保留最近 8 条原文消息，不写死固定条数
- 实现 SQLite 版 Spring AI `ChatMemory` 适配与 `CogninoteMemoryAdvisor`
- RAG 从手动 `{context}` 拼接改为 `CogninoteDocumentRetriever` + Spring AI `RetrievalAugmentationAdvisor`
- `useKnowledgeBase=false` 只挂会话记忆 Advisor；`useKnowledgeBase=true` 同时挂记忆和 RAG Advisor
- 用户显式停止保存部分 assistant 为 `STOPPED`；普通 SSE 断开继续生成并保存完整回答

### Milestone 14：macOS Apple Silicon 独立打包

- 新增 macOS 独立 Tauri 配置、Shell 脚本和 GitHub Actions workflow
- macOS 后端 app-image 输出到 `target/desktop-macos/backend/CogniNoteBackend.app/`
- macOS bundle 只产出 `.app` 和 `.dmg`，不复用 Windows NSIS 配置
- Tauri 启动逻辑按平台定位 Windows exe 或 macOS `.app/Contents/MacOS` 后端启动器
- macOS 桌面版数据和日志写入 `~/Library/Application Support/CogniNote/`

### Milestone 15：0.1.0 桌面 CI 双模式分发

- 版本统一到 `0.1.0`，后端 Jar 使用稳定文件名
- Windows CI 无证书时上传 unsigned 测试包，有 PFX Secret 时对 release exe 和 NSIS installer 做 Authenticode 签名
- macOS CI 无证书时上传 unsigned 测试包，有 Developer ID 证书时签名、公证并 staple `.app` / `.dmg`
- 双平台 workflow artifacts 使用 `0.1.0`、平台、架构和 `unsigned` / `signed` 命名
- 文档补充 GitHub Secrets、Gatekeeper、SmartScreen 和安装验收说明

### Milestone 16：MyBatis 统一数据访问层

- 引入原生 MyBatis + XML Mapper，不引入 MyBatis-Plus
- 文档、知识库目录、模型配置、聊天会话和消息 SQL 统一迁移到 Mapper XML
- Repository 保留为业务数据访问门面，Service/Controller 不直接依赖 Mapper
- schema 初始化、补列、旧 `model_config` 迁移改由 MyBatis Mapper 执行
- 测试清库改为测试专用 Mapper，生产和测试代码不再直接使用 `JdbcTemplate`
- 会话列表聚合消息数，避免 N+1 `countMessages`；目录删除使用集合级 SQL 清理 chunk

### Milestone 17：Element Plus 设置中心

- 引入 Element Plus 作为设置中心的基础交互组件库
- `/settings` 重构为左侧分组导航，系统、知识库、模型各自承载独立设置项
- 知识库页拆分为目录管理和检索测试，并补充未配置 Embedding 模型时的引导提示
- 模型页按左侧导航进入对话模型或 Embedding 模型，移除页面内重复切换按钮
- 修正设置页输入框、深色主题、模型配置布局和一键回到顶部体验
- 知识库配置页补充“重建全部索引”入口，并用弹窗提示用户重建索引

### Milestone 18：路由式多智能体 Agent 与模式隔离记忆

- 新增 `GENERAL_CHAT` 普通对话 Agent 和 `KNOWLEDGE_BASE` 知识库 Agent
- `ChatAgentRouter` 根据 `useKnowledgeBase` 确定路由，不引入额外 LLM 意图路由
- `GeneralChatAgent` 只挂会话记忆，不检索知识库，不返回引用来源
- `KnowledgeBaseChatAgent` 挂会话记忆和 `RetrievalAugmentationAdvisor`，继续返回本轮 RAG sources
- `chat_messages.agent_type` 标记消息所属 Agent，schema 初始化负责旧库补列
- 跨 Agent assistant 历史只作为带边界说明的参考注入，避免关闭知识库后继续执行 RAG 拒答规则

### Milestone 19：桌面安装、卸载与升级可靠性

- macOS unsigned 包降级为技术测试 artifact，普通用户分发以 signed、notarized、stapled DMG 为准
- macOS CI signed 模式验证主 app、嵌套后端 app 和 DMG 的签名、公证、staple 与 Gatekeeper 状态
- Tauri 桌面壳增加 single-instance，第二次启动会聚焦现有窗口
- Tauri 启动日志记录桌面壳版本、包版本、实际启动路径、后端资源路径和端口
- Windows NSIS 安装/卸载钩子负责关闭旧主程序和后端进程，并清理旧安装目录中的 backend 资源和常见快捷方式残留
- 设置中心系统信息显示后端版本、前端版本、桌面壳版本和桌面模式，便于确认用户实际启动的新旧版本

### Milestone 20：代码友好的检索准确率优化

- `TextChunker` 保护 Markdown fenced code block、Mermaid、PlantUML 和常见代码块缩进
- Lucene 正文使用 `SmartChineseAnalyzer`，代码/标识符字段使用 `StandardAnalyzer`
- 从 chunk 原文派生代码标识符、路径、异常名、流程图节点等 BM25 索引文本，但展示和 RAG 上下文仍回读 SQLite 原文
- HYBRID 检索扩大候选集，并用加权 RRF 替代 min-max 分数融合
- Embedding 网关拆分 `embedDocuments` 和 `embedQuery`
- DashScope 通过 `textType=document/query` 区分向量语义，OpenAI-compatible 继续使用 Spring AI OpenAI 标准 `/embeddings`

### Milestone 21：模型驱动的追问补全 Agent

- `KnowledgeBaseChatAgent` 在知识库检索前调用内部 `QueryContextualizerAgent`
- 补全 Agent 复用 active Chat 模型，但使用独立严格 JSON Prompt，不复用普通对话或 RAG 回答 Prompt
- 省略式追问只改写检索 query，不改变用户原始消息、SQLite 聊天记录、HTTP API 或 SSE 协议
- `CogninoteDocumentRetriever` 区分 `originalQuestion` 和 `retrievalQuery`，检索使用补全后的 query，最终回答仍面向原始问题
- `CogninoteRagQueryAugmenter` 注入“用户原始问题 / 知识库检索问题”边界，防止模型被无关片段带偏
- 非法 JSON、字段缺失、空 query、过长 query 或模型调用异常时统一回退原问题检索，不阻断主对话

### Milestone 22：对话上下文窗口与 Token 估算优化

- Chat 模型配置新增 `contextWindowTokens`，默认 `128000`，前端显示 `128K`
- `model_configs` 新增 `context_window_tokens`，Embedding 配置保持 `null`
- 聊天历史预算从固定 `6000` token 改为基于 active Chat 上下文窗口动态计算，历史最多使用约 80% 窗口
- `minimum-recent-messages` 默认保持 `8`，`summarize-after-messages` 默认调整为 `200`，token 超预算优先触发压缩
- 引入 JTokkit 作为本地 tokenizer，DashScope/Qwen 默认 `o200k_base`，OpenAI-compatible 识别失败时回退 `cl100k_base`
- 新增 `ChatContextUsageResponse`，并在 `ChatSessionResponse`、SSE `meta` 和 SSE `done` 中返回当前上下文占用
- 聊天页发送区以圆环进度展示上下文占用和“已压缩”状态，悬停展示详细估算数据

### Milestone 23：知识库追问补全自动触发与前端可配置

- 新增 `QueryContextualizerMode`：`AUTO`、`ALWAYS`、`OFF`，默认 `AUTO`
- 新增 SQLite `app_settings` 保存全局聊天设置，优先于环境变量和旧 enabled 开关
- 新增 `GET /api/chat/settings`、`PUT /api/chat/settings`
- `AUTO` 模式使用本地打分器判断短句、省略、指代、延续动作和完整问题反向信号，不做精确短语命中
- 补全 Prompt 输入改为“会话摘要 + 最近原文消息 + 当前问题”
- 设置页“模型 -> 对话模型”暴露追问补全策略，并说明它只影响知识库检索 query，不改写聊天原文，不影响纯模型对话

## 12. 后续版本规划

### v0.2

- Qdrant 适配
- HTML 解析
- 老式 `.doc` 解析
- 更完整的检索重排
- 系统托盘

### v0.3

- OCR 图片 PDF
- WebSearchTool
- 自动生成本地知识条目
- 知识构建模式

### v0.4

- MCP Server
- 外部 Agent 调用
- Skill 插件机制
- Obsidian 深度集成

## 13. 技术风险

### 13.1 范围过大

最大风险不是技术实现，而是第一版范围失控。

控制方式：

- 第一版只做 Lucene
- 第一版只做 `.md`、`.txt`、`.docx` 和文本型 `.pdf`
- 第一版只做问答和溯源
- 其它都排到后续版本

### 13.2 模型接口兼容

不同 OpenAI-compatible 服务实现不完全一致。

控制方式：

- 先支持最标准的 `/chat/completions`、`/embeddings` 和 `/models`
- 对 Ollama、LM Studio 等做单独兼容测试
- 模型配置页必须提供“测试连接”

### 13.3 本地文件变化

用户会修改、删除、移动笔记文件。

控制方式：

- 保存文件路径、修改时间、内容哈希
- 启动后允许扫描差异
- 删除文件后标记索引失效

### 13.4 Windows 权限问题

安装目录通常不适合写数据。

控制方式：

- 数据默认写入 `%APPDATA%/CogniNote`
- 用户可以在设置页修改数据目录

## 14. 结论

CogniNote Agent 第一版应聚焦为：

> 一个 Java + Vue 实现的本地 Markdown / TXT / DOCX / 文本型 PDF 知识库问答工具，核心卖点是 SQLite + Lucene 的清晰存储分工、Lucene 混合检索、模型可配置、答案可溯源，并能打包成 Windows 桌面应用一键运行。

只要第一版把这个闭环做扎实，它就已经不是普通 RAG Demo，而是一个能展示 Java 工程能力、搜索引擎能力、前端产品能力和 AI 应用落地能力的完整开源项目。
