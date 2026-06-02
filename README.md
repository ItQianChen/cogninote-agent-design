# CogniNote Agent

CogniNote Agent 是一个 Java + Vue 实现的本地个人知识库智能体。当前项目处于第三阶段：Lucene 检索闭环。

## 当前阶段目标

- Spring Boot 后端稳定启动。
- Vue 3 前端可以独立开发。
- 前端 `/api` 请求可以代理到本地后端。
- Spring Boot 可以托管 Vue 打包后的静态页面。
- 启动后初始化本地数据目录。
- 导入 Markdown、TXT、DOCX、文本型 PDF 到 SQLite。
- 使用 Lucene 建立关键词索引，并提供索引状态、重建和搜索 API。
- 通过 Spring AI 抽象预留 Embedding 能力，默认 provider 为 Spring AI Alibaba DashScope。

## 环境要求

- JDK 25。
- Maven 3.9+。
- Node.js 20.19.6 或兼容版本。
- npm 10.8.2 或兼容版本。

当前 Maven Enforcer 会拒绝非 JDK 25 的运行环境。Spring Boot 3.5.14 官方兼容到 Java 25，项目统一使用本机 JDK 25 构建。

## 后端开发

```powershell
mvn test
mvn spring-boot:run
```

后端默认监听：

```text
http://127.0.0.1:18080
```

系统状态接口：

```text
GET http://127.0.0.1:18080/api/system/status
```

检索接口：

```text
GET  http://127.0.0.1:18080/api/index/status
POST http://127.0.0.1:18080/api/index/rebuild
POST http://127.0.0.1:18080/api/search
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

## Embedding 配置

默认不启用 Embedding，因此应用在没有 DashScope API Key 时仍能启动，关键词检索可用。

启用 Spring AI Alibaba DashScope Embedding：

```powershell
$env:COGNINOTE_AI_EMBEDDING_PROVIDER="dashscope"
$env:COGNINOTE_DASHSCOPE_API_KEY="your-api-key"
$env:COGNINOTE_EMBEDDING_MODEL="text-embedding-v4"
```

启用后可以使用 `VECTOR` 和 `HYBRID` 检索模式。未启用时，这两种模式会返回明确错误，`KEYWORD` 模式不受影响。

## 前端开发

```powershell
cd cogniNote-agent-front
npm ci
npm run dev
```

Vite 开发服务器会把 `/api` 代理到 `http://127.0.0.1:18080`。

## 整包构建

```powershell
mvn -Pwith-frontend package
java -jar target/cogninote-agent-design-0.0.1-SNAPSHOT.jar
```

`with-frontend` profile 会执行前端构建，并把 `cogniNote-agent-front/dist` 复制到 Spring Boot Jar 的静态资源目录。
