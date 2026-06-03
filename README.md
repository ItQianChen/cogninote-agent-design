# CogniNote Agent

CogniNote Agent 是一个 Java + Vue 实现的本地个人知识库智能体。当前项目处于第六阶段：桌面应用交付。

## 当前阶段目标

- Spring Boot 后端稳定启动，统一使用 JDK 25。
- Vue 3 前端可以独立开发，并通过 Vite 代理访问后端 `/api`。
- Spring Boot 可以托管 Vue 打包后的静态页面。
- 启动后初始化本地数据目录。
- 导入 Markdown、TXT、DOCX、文本型 PDF 到 SQLite。
- 使用 Lucene 建立关键词索引，并提供索引状态、重建和搜索 API。
- 默认通过 Spring AI Alibaba DashScope 提供百炼 Embedding 和 Chat 能力，并支持 OpenAI-compatible 自定义服务。
- 支持“配置模型 -> 提问 -> 混合检索 -> 构造 Prompt -> SSE 流式回答 -> 展示引用来源”的 RAG 对话闭环。
- 支持在模型配置页选择 Provider、获取模型列表，并选择默认 Chat/Embedding 模型。
- 支持通过 Tauri 2 桌面壳启动 Spring Boot 后端，并在桌面窗口中加载本地页面。

## 环境要求

- JDK 25。
- Maven 3.9+。
- Node.js 20.19.6 或兼容版本。
- npm 10.8.2 或兼容版本。
- Rust stable toolchain、Cargo、MSVC Build Tools 和 WebView2 Runtime，用于 Windows 桌面打包。

当前 Maven Enforcer 会拒绝非 JDK 25 的运行环境。

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

## 后端开发

```powershell
mvn test
mvn spring-boot:run
```

后端默认监听：

```text
http://127.0.0.1:18080
```

首次启动会创建本地数据目录：

```text
%APPDATA%/CogniNote/
  config/
  data/
  index/lucene/
  logs/
```

也可以用环境变量覆盖：

```powershell
$env:COGNINOTE_PORT="18081"
$env:COGNINOTE_DATA_DIR="D:\CogniNoteData"
```

## 前端开发

```powershell
cd cogniNote-agent-front
npm ci
npm run dev
```

Vite 开发服务器会把 `/api` 代理到 `http://127.0.0.1:18080`。

## 模型配置

前端“模型配置”页支持两种 Provider：

- `DASHSCOPE`：阿里百炼默认通道。Base URL 固定展示为 `https://dashscope.aliyuncs.com/api/v1`；Chat/Embedding 调用使用 Spring AI Alibaba 原生 DashScope 客户端，模型列表仍走百炼兼容 `/models`。
- `OPENAI_COMPATIBLE`：通用 OpenAI-compatible 通道。Base URL 由用户填写，后端会调用 `Base URL + /models`、`Base URL + /chat/completions` 和 `Base URL + /embeddings`。

默认参数：

- Chat 模型默认 `qwen-plus`。
- Embedding 模型默认 `text-embedding-v4`。
- Embedding 维度默认 `1024`。
- Temperature 默认 `0.7`。
- Top K 默认 `8`。

获取模型列表时，后端会按当前 Provider 选择端点：DashScope 使用 `https://dashscope.aliyuncs.com/compatible-mode/v1/models`，OpenAI-compatible 使用用户 Base URL 拼接 `/models`。模型能力按 ID 做轻量分类：包含 `embedding` 或 `embed` 的归为 Embedding，其余默认归为 Chat。获取失败时仍可手动输入模型 ID 保存。

实现注意：DashScope SDK 示例中的 HTTP API Root 是 `https://dashscope.aliyuncs.com/api/v1`。Spring AI Alibaba 的 `DashScopeApi` 内部 path 已包含 `/api/v1/services/...`，所以后端构造 Spring AI Alibaba 客户端时会把该地址转换成 `https://dashscope.aliyuncs.com`，避免拼出重复 `/api/v1`。

注意：不要把自定义 OpenAI-compatible URL 配到 `DASHSCOPE` Provider。需要自定义 URL 时请选择 `OPENAI_COMPATIBLE`；阿里百炼 Provider 会始终使用默认 `DashScopeBaseUrls`。

API Key 当前以开发态明文保存到本机 SQLite：`%APPDATA%/CogniNote/data/cogninote.db`。这只是当前开发阶段为了打通闭环的取舍，不适合作为最终交付安全方案；后续应改为 Windows 本地加密或凭据管理。

Phase 3 的环境变量 fallback 仍然保留：

```powershell
$env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
$env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
$env:COGNINOTE_EMBEDDING_MODEL="text-embedding-v4"
```

有 SQLite 模型配置时优先使用 SQLite；没有配置时，Embedding 会回退到上述环境变量。

## API

普通 JSON API 统一返回 `ApiResponse<T>`：

```json
{
  "success": true,
  "code": "OK",
  "message": "OK",
  "data": {},
  "timestamp": 1780000000000
}
```

错误返回保持同形状，`success=false`，`data=null`。`POST /api/chat/stream` 是 `text/event-stream`，不包装；`DELETE /api/documents/{id}` 成功时仍返回 `204 No Content`。

系统状态：

```text
GET /api/system/status
```

文档导入：

```text
GET    /api/documents
POST   /api/documents/ingest
DELETE /api/documents/{id}
```

检索与索引：

```text
GET  /api/index/status
POST /api/index/rebuild
POST /api/search
```

模型配置：

```text
GET  /api/model-config
PUT  /api/model-config
POST /api/model-config/test
POST /api/model-config/models
```

RAG 流式对话：

```text
POST /api/chat/stream
```

请求示例：

```json
{
  "question": "这个项目如何打包？",
  "topK": 8,
  "mode": "HYBRID"
}
```

SSE 事件顺序：

```text
event: meta
event: delta
event: done
event: error
```

若 `HYBRID` 或 `VECTOR` 因 Embedding 不可用失败，RAG 服务会自动降级到 `KEYWORD`，并在 `meta.retrievalMode` 中返回实际检索模式。

## 整包构建

```powershell
mvn -Pwith-frontend package
java -jar target/cogninote-agent-design-0.0.1-SNAPSHOT.jar
```

`with-frontend` profile 会执行前端构建，并把 `cogniNote-agent-front/dist` 复制到 Spring Boot Jar 的静态资源目录。

## 桌面应用构建

第六阶段使用 `Tauri 2 + jpackage app-image` 交付 Windows 桌面应用。Tauri 只负责窗口、进程管理和安装包；文档摄入、SQLite、Lucene、模型配置和 RAG 仍由 Java 后端负责。

先检查桌面工具链：

```powershell
.\scripts\verify-desktop-toolchain.ps1
```

生成自带 Java runtime 的后端运行目录：

```powershell
.\scripts\build-desktop-backend.ps1
```

完整桌面打包：

```powershell
.\scripts\build-desktop-app.ps1
```

关键约束：

- 后端 app-image 位于 `target/desktop/backend/CogniNoteBackend/`，Tauri 会把整个目录作为资源打包，不能只复制 `CogniNoteBackend.exe`。
- `jpackage` 输入目录位于 `target/desktop/jpackage-input/`，只放最终 Spring Boot fat jar，避免把 `target/` 下的测试目录和中间产物打进 app-image。
- Tauri 启动时会选择 `18080-18120` 的可用端口，并通过 `COGNINOTE_PORT` 注入后端。
- 桌面窗口加载 `http://127.0.0.1:{port}/`，所以前端现有 `/api` 相对路径和 SSE 流式接口不需要改。
- 后端启动日志写入 `%APPDATA%/CogniNote/logs/desktop-backend.log`。
- 当前阶段 API Key 仍是开发态明文 SQLite 存储；公开安装包验收前需要补桌面会话令牌或本地加密方案。

## 工程结构

后端按全局三层组织，每层下再按业务领域分包：

```text
src/main/java/com/itqianchen/agentdesign/
  controller/{document,search,index,model,chat,system}
  service/{document,search,index,model,chat,system}
  repository/{document,model}
  domain/{document,search,model,chat,storage,ingestion}
  dto/{document,search,index,model,chat,system}
  common/api
```

Controller 只处理 HTTP、校验和响应包装；Service 承担业务编排；Repository 只访问 SQLite/JDBC；领域对象和 DTO 分开，避免数据库记录、接口响应和业务编排互相污染。

前端按 Vue Router + Pinia + API client 拆分：

```text
cogniNote-agent-front/src/
  api/          # 统一 HTTP client、业务 API、POST SSE 解析
  stores/       # system/documents/search/modelConfig/chat 状态
  router/       # chat/knowledge/model-config/settings 路由
  views/        # 页面级组件
  components/   # 应用壳、导航、列表、统计、分段控件等复用组件
  styles/       # 全局基础样式
  utils/        # 时间、文件大小、分数格式化
```

本次可维护性重构的完整计划和验收记录见 [docs/maintainability-refactor-plan.md](docs/maintainability-refactor-plan.md)。

第五阶段计划已调整为先完善模型配置能力：阿里百炼使用默认 DashScope 通道，OpenAI-compatible 使用用户自定义 Base URL，自动获取模型列表，并让用户选择默认 Chat/Embedding 模型；完整计划见 [docs/phase-5-model-configuration-plan.md](docs/phase-5-model-configuration-plan.md)。

第六阶段计划聚焦桌面应用交付：Tauri 2 负责桌面窗口和后端进程生命周期，jpackage 负责生成自带 Java runtime 的后端 app-image；完整计划见 [docs/phase-6-desktop-app-delivery-plan.md](docs/phase-6-desktop-app-delivery-plan.md)。

## 注释规范

代码注释只写有维护价值的内容：解释为什么这样做、这里有什么约束、修改时要注意什么。复杂事务边界、索引失败降级、RAG 检索降级、SSE 手动解析、API Key 复用和外部模型实例缓存等非显然逻辑必须保留简洁注释；简单自解释代码不逐行注释，避免噪音。
