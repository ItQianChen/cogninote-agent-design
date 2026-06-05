# CogniNote Agent

CogniNote Agent 是一个本地优先的个人知识库问答应用。它可以导入本机 Markdown、TXT、DOCX 和文本型 PDF，使用 SQLite 保存知识片段，使用 Lucene 建立关键词/向量混合检索索引，并通过可配置的大模型提供带引用来源的 RAG 问答。

当前项目面向 Windows 本地桌面交付：Tauri 负责桌面窗口和后端进程生命周期，Spring Boot 负责业务 API 和托管 Vue 页面。

适合的使用场景：

- 把本地笔记、项目文档和资料整理成可检索知识库。
- 对自己的文档提问，并要求答案带来源引用。
- 在本机运行 RAG 应用，不把文档上传到托管知识库平台。

## 核心能力

- 本地文档导入：支持 Markdown、TXT、DOCX、文本型 PDF。
- 知识库目录管理：支持导入本地目录、启用/停用、删除目录、局部重建索引和按目录展开文档。
- 本地数据存储：SQLite 保存文档元数据、chunk 内容、模型配置、聊天会话和消息。
- 本地搜索索引：Lucene 提供 BM25 关键词检索、向量检索和混合检索；向量检索会使用 active Embedding 模型生成查询向量。
- RAG 对话：通过 Spring AI ChatClient + Advisor 注入会话记忆和知识库片段，保留空白的 SSE 流式输出答案，并展示引用来源。
- 模型配置：支持阿里百炼 DashScope 默认通道，也支持 OpenAI-compatible 自定义 Base URL。
- 对话式桌面界面：左侧持久化会话列表，主区域流式对话，答案按 AI 流式 Markdown 渲染，引用来源可折叠，对话设置可切换知识库、检索模式和 Top K。
- 主题设置：支持深色/夜间和日间主题，本机保存偏好。
- 桌面交付：支持构建 Windows 桌面程序和 NSIS 安装包。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 25, Spring Boot 3.5, Spring JDBC |
| 前端 | Vue 3, Vue Router, Pinia, Vite |
| 存储 | SQLite |
| 检索 | Apache Lucene |
| 模型 | Spring AI Alibaba DashScope, Spring AI OpenAI Runtime for OpenAI-compatible |
| 桌面 | Tauri 2, jpackage, NSIS |

## 快速开始

### 环境要求

- JDK 25
- Maven 3.9+
- Node.js 20.19.6 或兼容版本
- npm 10.8.2 或兼容版本

Windows 桌面打包还需要 Rust stable toolchain、MSVC Build Tools 和 WebView2 Runtime。完整说明见 [桌面构建指南](docs/desktop-build-guide.md)。

项目会校验 JDK 版本。Windows 下可先设置：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

### 启动后端

```powershell
mvn spring-boot:run
```

默认地址：

```text
http://127.0.0.1:18080
```

首次启动会创建本地数据目录：

```text
%APPDATA%\CogniNote\
```

### 启动前端开发环境

```powershell
cd cogniNote-agent-front
npm ci
npm run dev
```

Vite 会把 `/api` 代理到 `http://127.0.0.1:18080`。

### 构建整包 Jar

```powershell
mvn -Pwith-frontend package
java -jar target/cogninote-agent-design-0.0.1-SNAPSHOT.jar
```

`with-frontend` profile 会构建 Vue，并把 `cogniNote-agent-front/dist` 复制进 Spring Boot 静态资源目录。

### 构建桌面应用

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

构建完成后主要产物为：

```text
cogniNote-agent-front/src-tauri/target/release/cogninote-agent.exe
cogniNote-agent-front/src-tauri/target/release/bundle/nsis/CogniNote_0.0.1_x64-setup.exe
```

注意：`target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 只是后端 app-image 的启动器，不是最终桌面应用入口。

## 使用流程

1. 打开应用。
2. 进入“设置”页，在“模型”区域选择 Provider，填写 API Key，并分别配置 Chat / Embedding 模型；RAG 回答使用 Chat 模型，向量检索和混合检索使用 Embedding 模型。
3. 在“设置”页的“知识库”区域导入本地文档目录。
4. 使用搜索面板验证索引命中结果。
5. 回到“对话”页提问，查看 AI 流式 Markdown 答案和可折叠引用来源；生成中点击停止会显式取消本次模型流。

模型配置细节见 [模型配置指南](docs/model-configuration-guide.md)。

## 数据与隐私

CogniNote 默认把数据写入：

```text
%APPDATA%\CogniNote\
  data\cogninote.db
  index\lucene\
  logs\app.log
  logs\desktop-backend.log
```

SQLite 是业务事实来源，Lucene 是可重建索引。应用不会复制用户原始文件，只保存解析后的 chunk 文本、文档元数据、聊天记录、索引数据和模型配置。

`app.log` 是 Spring Boot 业务日志，`desktop-backend.log` 是桌面壳启动后端时的 stdout/stderr 日志。定位桌面启动、模型连接、RAG 对话和索引问题时优先查看这两个文件。

当前开发阶段 API Key 仍以明文保存到本机 SQLite。公开发布前应改为 Windows 本地加密或凭据管理，并补充桌面会话令牌保护。

## 架构概览

```text
Tauri Desktop Shell
  └─ loads http://127.0.0.1:{port}/

Spring Boot Backend
  ├─ Document Ingestion
  ├─ SQLite Repository
  ├─ Lucene Knowledge Store
  ├─ Model Configuration
  ├─ AI Runtime
  ├─ Chat Memory
  └─ Agent Chat SSE (Whitespace Preserving)

Vue Frontend
  ├─ Chat Shell
  ├─ Persistent Sessions
  ├─ AI Streaming Markdown Renderer
  └─ Settings
      ├─ System & Theme
      ├─ Knowledge
      └─ Model Config
```

后端按 controller / service / repository / domain / dto 分层；前端按 router / stores / api / views / components 分层。完整设计见 [项目方案](docs/cogninote-agent-design.md)。

## 常用命令

```powershell
# 后端测试
mvn test

# 前端构建
npm --prefix cogniNote-agent-front run build

# 后端 + 前端整包
mvn -Pwith-frontend package

# 桌面工具链检查
.\scripts\verify-desktop-toolchain.ps1

# 桌面应用打包
.\scripts\build-desktop-app.ps1 -SkipTests
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [项目方案](docs/cogninote-agent-design.md) | 产品定位、架构、数据模型和里程碑 |
| [API 参考](docs/api-reference.md) | REST API、统一响应格式、SSE 事件和流式取消接口 |
| [模型配置指南](docs/model-configuration-guide.md) | DashScope 与 OpenAI-compatible 配置方式 |
| [桌面构建指南](docs/desktop-build-guide.md) | PowerShell 脚本、Tauri 打包、产物和故障排查 |
| [可维护性重构计划](docs/maintainability-refactor-plan.md) | 前后端分层、统一响应和注释规范 |
| [Phase 6 计划](docs/phase-6-desktop-app-delivery-plan.md) | Windows 桌面交付阶段计划 |
| [Phase 9 计划](docs/phase-9-ui-visual-readability-plan.md) | UI 视觉可读性与主题系统修正 |
| [Phase 10 计划](docs/phase-10-knowledge-base-folders-plan.md) | 知识库目录管理与局部索引重建 |
| [Phase 11 计划](docs/phase-11-agent-model-runtime-refactor-plan.md) | 智能体模型运行时重构 |
| [Phase 12 计划](docs/phase-12-ai-markdown-rendering-plan.md) | AI 流式 Markdown 渲染重构 |
| [Phase 13 计划](docs/phase-13-chat-memory-and-spring-ai-rag-plan.md) | SQLite 聊天记忆与 Spring AI RAG Advisor 重构 |

阶段计划文档保留在 `docs/phase-*.md`，用于追踪项目演进，不作为最终用户手册。

## 开发状态

当前项目已完成文档摄入、Lucene 搜索、模型配置、RAG 对话、智能体模型运行时重构、AI 流式 Markdown 渲染、SQLite 聊天记忆、纯模型对话、空白保真的 SSE 流式输出和 Windows 桌面打包的主要闭环。仍需重点补齐：

- API Key 本地加密或凭据管理。
- 桌面会话令牌保护。
- 更完整的发布验收和安装包测试。
- 托盘、自动更新、代码签名等桌面增强能力。

## License

本项目使用 [Apache License 2.0](LICENSE)。
