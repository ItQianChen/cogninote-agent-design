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
- 模型开放：使用 Spring AI 作为模型抽象层，默认通过 Spring AI Alibaba 接入 DashScope / 百炼 / Qwen 等模型服务，同时保留后续接入 OpenAI-compatible、Ollama、LM Studio 等实现的空间

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
  ├─ 知识库管理
  ├─ 模型配置
  └─ 系统设置

Spring Boot 后端
  ├─ Chat API
  ├─ Agent / RAG 调度
  ├─ KnowledgeStore 接口
  ├─ LuceneKnowledgeStore
  ├─ Document Ingestion Pipeline
  ├─ Embedding Gateway (Spring AI 抽象，默认 Spring AI Alibaba / DashScope)
  ├─ LLM Gateway (Spring AI 抽象，默认 Spring AI Alibaba / DashScope)
  ├─ Metadata Service
  └─ App Launcher

本地数据目录
  ├─ SQLite 元数据与 Chunk 文本
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

- SQLite：保存文档元数据、Chunk 文本、模型配置和应用设置
- Lucene：保存 BM25 字段、向量字段和检索必要字段
- 原始文件：仍保留在用户自己的目录里，应用不复制原文件

检索时先由 Lucene 返回 `chunk_id`，再用 `chunk_id` 到 SQLite 查询 `chunks.content`，最后组装 RAG Prompt。

### 5.3 混合检索

检索方式：

- 关键词检索：BM25
- 语义检索：向量相似度
- 混合检索：两类结果合并重排

默认权重：

```text
BM25: 0.6
Vector: 0.4
```

后续可在设置页开放调整。

### 5.4 Embedding Gateway

嵌入模型不放进 JVM。CogniNote 使用 Spring AI 的 `EmbeddingModel` 作为模型抽象，默认实现选择 Spring AI Alibaba DashScope。业务代码只依赖 CogniNote 自己的 `EmbeddingGateway` / Spring AI 通用接口，不直接散落依赖 Alibaba 具体类。

第一版推荐默认接入：

- Spring AI Alibaba DashScope / 百炼 embedding
- 后续可替换为 Spring AI OpenAI、Ollama、DeepSeek 或其它 OpenAI-compatible 实现

统一接口：

```java
public interface EmbeddingClient {
    float[] embed(String text);

    List<float[]> embedBatch(List<String> texts);
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

未配置 API Key 时，关键词检索仍应可用；向量索引和混合检索需要明确提示 Embedding 未启用。Spring AI Alibaba 的若干 DashScope 自动配置默认会尝试创建模型 Bean，因此第三阶段默认把 `spring.ai.model.embedding.text` 设为 `none`，并关闭 Agent/Chat/Image 等未使用模块；启用向量检索时再通过环境变量打开 DashScope Embedding。

### 5.5 LLM Gateway

所有大模型调用统一通过 LLM Gateway。对话层同样使用 Spring AI 的 `ChatModel` / `ChatClient` 抽象，默认实现接 Spring AI Alibaba DashScope。不要让前端或 RAG 业务逻辑直接绑定某个厂商 SDK。

用户在前端配置：

- Provider 类型
- Base URL（仅 OpenAI-compatible 可自定义；DashScope 使用默认百炼地址）
- API Key
- 默认模型

后端接口：

```text
GET    /api/model-config
PUT    /api/model-config
POST   /api/model-config/test
POST   /api/model-config/models
```

第五阶段先把模型配置页做扎实：用户选择 `DASHSCOPE` 时使用默认百炼通道，配置页展示 `https://dashscope.aliyuncs.com/api/v1`；选择 `OPENAI_COMPATIBLE` 时输入自定义 Base URL，后端按 `Base URL + /models`、`Base URL + /chat/completions`、`Base URL + /embeddings` 调用通用接口。第一版仍只维护一个 active 配置，多 provider 列表管理放到后续。

API Key 第四、五阶段仍以开发态明文保存到 SQLite；本地加密或 Windows 凭据管理放到安全加固阶段，不能在最终交付版本继续明文保存。

### 5.6 RAG 问答流程

```text
用户提问
  ↓
生成查询向量
  ↓
Lucene 混合检索
  ↓
取 Top K 片段
  ↓
构造 Prompt
  ↓
调用 LLM
  ↓
流式返回答案
  ↓
附带引用来源
```

回答必须包含引用来源，避免变成不可验证的聊天工具。

## 6. 前端页面设计

### 6.1 对话页

核心功能：

- 输入问题
- 流式显示回答
- 显示引用来源
- 点击来源查看原文片段
- 顶部显示当前模型

### 6.2 知识库管理页

核心功能：

- 添加本地文件夹
- 查看已索引文档
- 查看索引状态
- 手动重新索引
- 删除文档索引

### 6.3 模型配置页

核心功能：

- 选择阿里百炼或 OpenAI-compatible Provider
- 为 OpenAI-compatible 输入 Base URL；DashScope 使用默认地址
- 输入 API Key
- 测试连接
- 自动拉取模型列表
- 选择默认对话模型
- 选择默认 Embedding 模型

### 6.4 设置页

第一版设置项：

- 数据目录
- 索引目录
- 检索 Top K
- 混合检索权重
- 是否开机自动启动，后续可做

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
      └─ app.log
```

不要默认把数据写在安装目录下。Windows 安装目录可能没有写权限。

## 8. 数据存储设计

第一版采用 SQLite + Lucene 的组合。

```text
SQLite = 业务事实来源
Lucene = 可重建的搜索索引
原始文件 = 用户自己的文件，不复制
```

不要只用 SQLite，因为 SQLite 不适合承担高质量全文检索、BM25 和向量召回。

也不要只用 Lucene，因为 Lucene 索引更适合搜索，不适合保存模型配置、文档状态、任务状态等业务数据。

### 8.1 SQLite 存什么

SQLite 保存结构化业务数据和 RAG 需要回读的 Chunk 内容。

核心表：

```sql
CREATE TABLE documents (
    id TEXT PRIMARY KEY,
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

CREATE TABLE llm_providers (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT,
    active_model TEXT,
    is_active INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER,
    updated_at INTEGER
);

CREATE TABLE app_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER
);
```

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
embedding_vector
preview_text
```

`content_for_bm25` 可用于检索，但不要求作为展示数据来源。真正展示和喂给模型的文本，以 SQLite 中的 `chunks.content` 为准。

### 8.3 检索回读流程

```text
用户提问
  ↓
生成查询向量
  ↓
Lucene 执行 BM25 + 向量混合检索
  ↓
返回 chunk_id 列表
  ↓
SQLite 查询 chunks.content 与 documents 元数据
  ↓
组装 Prompt
  ↓
调用 LLM
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

推荐第六阶段：

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
```

启动逻辑：

1. Tauri 主进程启动
2. Tauri 在 `18080-18120` 中选择可用端口
3. Tauri 设置 `COGNINOTE_PORT` 并启动 `jpackage` 后端 app-image
4. Spring Boot 只监听 `127.0.0.1`
5. Tauri 等待 `/api/system/status` 健康检查通过
6. Tauri WebView 加载 `http://127.0.0.1:{port}/`

注意：`jpackage --type app-image` 的产物依赖 `app/` 和 `runtime/` 目录，不能只把 `CogniNoteBackend.exe` 作为单文件复制。`jpackage` 输入目录只放最终 Spring Boot fat jar，Tauri 则把完整 `target/desktop/backend/CogniNoteBackend/` 目录作为资源打包。

后续可增加系统托盘能力：

- 打开主界面
- 查看服务状态
- 退出应用

## 10. API 草案

```text
POST   /api/chat
GET    /api/documents
POST   /api/documents/ingest
DELETE /api/documents/{id}

GET    /api/index/status
POST   /api/index/rebuild

GET    /api/llm-providers
POST   /api/llm-providers
GET    /api/llm-providers/{id}/models
PUT    /api/llm-providers/{id}/activate
DELETE /api/llm-providers/{id}

GET    /api/settings
PUT    /api/settings
```

`/api/chat` 建议使用 SSE 流式返回。

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
- LLM Gateway
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
