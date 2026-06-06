# CogniNote Agent

CogniNote Agent 是一个本地优先的个人知识库问答应用。它可以导入本机 Markdown、TXT、DOCX 和文本型 PDF，使用 SQLite 保存知识片段，使用 Lucene 建立关键词/向量混合检索索引，并通过可配置的大模型提供带引用来源的 RAG 问答。

当前项目面向本地桌面交付：Tauri 负责桌面窗口和后端进程生命周期，Spring Boot 负责业务 API 和托管 Vue 页面。Windows 与 macOS 打包链路分开维护，避免平台资源和脚本互相污染。

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
- 桌面交付：支持构建 Windows 桌面程序和 NSIS 安装包，以及 macOS Apple Silicon `.app` / `.dmg` 独立打包链路；`0.1.0` 定位为可安装测试分发版，CI 未配置证书时产出 unsigned 测试包，配置证书后产出签名/公证分发包。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 后端 | Java 25, Spring Boot 3.5, MyBatis XML Mapper |
| 前端 | Vue 3, Vue Router, Pinia, Vite, Element Plus |
| 存储 | SQLite |
| 检索 | Apache Lucene |
| 模型 | Spring AI Alibaba DashScope, Spring AI OpenAI Runtime for OpenAI-compatible |
| 桌面 | Tauri 2, jpackage, NSIS, macOS app/dmg |

## 快速开始

### 环境要求

- JDK 25
- Maven 3.9+
- Node.js 20.19.6 或兼容版本
- npm 10.8.2 或兼容版本

Windows 桌面打包还需要 Rust stable toolchain、MSVC Build Tools 和 WebView2 Runtime。macOS 桌面打包第一版只支持 Apple Silicon，需要 JDK 25 arm64、Rust stable 和 Xcode Command Line Tools。GitHub Actions 无证书时可生成 unsigned 测试包；普通用户分发包还需要配置代码签名和公证 Secrets，完整说明见 [桌面构建指南](docs/desktop-build-guide.md)。

项目会通过 Maven Enforcer 校验 JDK 25，低版本会直接构建失败。Windows 下可先设置：

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
java -jar target/cogninote-agent-design.jar
```

`with-frontend` profile 会构建 Vue，并把 `cogniNote-agent-front/dist` 复制进 Spring Boot 静态资源目录。

### 构建 Windows 桌面应用

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

构建完成后主要产物为：

```text
cogniNote-agent-front/src-tauri/target/release/cogninote-agent.exe
cogniNote-agent-front/src-tauri/target/release/bundle/nsis/CogniNote_0.1.0_x64-setup.exe
```

注意：`target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 只是后端 app-image 的启动器，不是最终桌面应用入口。

也可以在 GitHub Actions 手动触发 `Desktop Windows` workflow 构建。workflow 会在 Windows runner 上安装 JDK 25、Node 和 Rust；未配置证书时上传 `CogniNote-0.1.0-windows-x64-unsigned-*` 测试 artifacts，配置 `WINDOWS_CERTIFICATE_PFX_BASE64` 和 `WINDOWS_CERTIFICATE_PASSWORD` 后上传 `signed` artifacts。

手动触发 workflow 时可设置 `publish_release=true`，把真实安装包 `.exe` 和便携包 `.zip` 发布到 GitHub Release；`release_tag` 留空时，unsigned 构建默认发布到 `v0.1.0-test.1`，signed 构建默认发布到 `v0.1.0`。

### 构建 macOS 桌面应用

macOS 和 Windows 打包链路分开维护。请在 Apple Silicon Mac 上执行：

```bash
bash ./scripts/build-desktop-app-macos.sh --skip-tests
```

构建完成后主要产物为：

```text
cogniNote-agent-front/src-tauri/target/release/bundle/macos/CogniNote.app
cogniNote-agent-front/src-tauri/target/release/bundle/dmg/CogniNote_0.1.0_aarch64.dmg
```

注意：`target/desktop-macos/backend/CogniNoteBackend.app` 只是 macOS 后端 app-image，不是最终桌面应用入口。

macOS 可以在 GitHub Actions `Desktop macOS` workflow 构建。未配置 Apple Developer 证书时会上传 `CogniNote-0.1.0-macos-arm64-unsigned-*` 测试 artifacts，普通用户可能遇到 Gatekeeper 拦截；配置 Developer ID 和公证 Secrets 后会上传 `signed` artifacts，避免下载后出现“已损坏，无法打开”的常见拦截。

手动触发 workflow 时可设置 `publish_release=true`，把真实 `.dmg` 和 `.app.zip` 发布到 GitHub Release；`release_tag` 留空时，unsigned 构建默认发布到 `v0.1.0-test.1`，signed 构建默认发布到 `v0.1.0`。给测试用户分发时优先发 Release 下载链接，不建议用微信直接转发 `.dmg`。

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

macOS 桌面版默认写入：

```text
~/Library/Application Support/CogniNote/
  data/cogninote.db
  index/lucene/
  logs/app.log
  logs/desktop-backend.log
```

SQLite 是业务事实来源，Lucene 是可重建索引。应用不会复制用户原始文件，只保存解析后的 chunk 文本、文档元数据、聊天记录、索引数据和模型配置。

`app.log` 是 Spring Boot 业务日志，`desktop-backend.log` 是桌面壳启动后端时的 stdout/stderr 日志。定位桌面启动、模型连接、RAG 对话和索引问题时优先查看这两个文件。

当前 `0.1.0` 仍把 API Key 明文保存到本机 SQLite，适合内部测试和小范围安装验证，不建议作为大范围公开生产发布。公开发布前应改为 Windows 本地加密或凭据管理，并补充桌面会话令牌保护。

## 架构概览

```text
Tauri Desktop Shell
  └─ loads http://127.0.0.1:{port}/

Spring Boot Backend
  ├─ Document Ingestion
  ├─ Repository + MyBatis Mapper
  ├─ Lucene Knowledge Store
  ├─ Model Configuration
  ├─ AI Runtime
  ├─ Chat Memory
  └─ Agent Chat SSE (Whitespace Preserving)

Vue Frontend
  ├─ Chat Shell
  ├─ Persistent Sessions
  ├─ AI Streaming Markdown Renderer
  └─ Settings Center
      ├─ System & Theme
      ├─ Knowledge & Search Test
      └─ Chat / Embedding Model Config
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

# macOS Apple Silicon 桌面应用打包
bash ./scripts/build-desktop-app-macos.sh --skip-tests
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [项目方案](docs/cogninote-agent-design.md) | 产品定位、架构、数据模型和里程碑 |
| [API 参考](docs/api-reference.md) | REST API、统一响应格式、SSE 事件和流式取消接口 |
| [模型配置指南](docs/model-configuration-guide.md) | DashScope 与 OpenAI-compatible 配置方式 |
| [桌面构建指南](docs/desktop-build-guide.md) | PowerShell 脚本、Tauri 打包、产物和故障排查 |
| [可维护性重构计划](docs/maintainability-refactor-plan.md) | 前后端分层、统一响应和注释规范 |


## 开发状态

当前项目已完成文档摄入、Lucene 搜索、模型配置、RAG 对话、路由式多智能体对话、模式隔离聊天记忆、智能体模型运行时重构、AI 流式 Markdown 渲染、SQLite 聊天记忆、纯模型对话、空白保真的 SSE 流式输出、MyBatis 统一数据访问层、Windows 桌面打包、macOS Apple Silicon 独立打包链路，以及 `0.1.0` 双平台 unsigned/signed CI 打包链路的主要闭环。仍需重点补齐：

- API Key 本地加密或凭据管理。
- 桌面会话令牌保护。
- 更完整的发布验收和安装包测试。
- 托盘、自动更新、Universal Binary、Intel Mac 支持等桌面增强能力。

## License

本项目使用 [Apache License 2.0](LICENSE)。
