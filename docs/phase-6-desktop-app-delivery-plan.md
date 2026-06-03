# CogniNote Agent 第六阶段任务计划：桌面应用交付（修订版）

## Summary

第六阶段目标是把当前 `Spring Boot 本地服务 + Vue 页面` 升级为 Windows 桌面应用：用户安装后双击 `CogniNote.exe`，Tauri 自动启动后端、等待健康检查、打开桌面窗口，并在关闭时清理后端进程。

主线方案继续采用：

```text
Tauri 2 桌面壳 + Spring Boot 后端 app-image + Spring Boot 托管 Vue dist
```

本阶段不重写前端、不迁移业务逻辑到 Rust、不把 `/api` 改成 Tauri IPC。当前项目已具备可行基础：后端已绑定 `127.0.0.1`，端口支持 `COGNINOTE_PORT` 覆盖，前端生产态使用相对 `/api`，适合由桌面 WebView 加载同源 Spring Boot 页面。

## Key Changes

- 新增 Tauri 2 桌面壳，位置放在 `cogniNote-agent-front/src-tauri/`。
- 后端继续使用 JDK 25 构建，通过 `jpackage --type app-image` 生成自带 Java runtime 的后端运行目录。
- Tauri 不把 `CogniNoteBackend.exe` 当单文件 sidecar，而是把完整 `jpackage` app-image 目录作为资源打包，并从资源目录启动其中的 exe。
- 启动流程固定为：选择 `18080-18120` 可用端口，设置 `COGNINOTE_PORT` 和 `COGNINOTE_DESKTOP=true`，启动后端，轮询 `/api/system/status`，成功后加载 `http://127.0.0.1:{port}/`。
- 新增桌面构建脚本：工具链检查、后端 jar 构建、后端 app-image 生成、Tauri 打包。
- 启动日志必须可见：后端 stdout/stderr 写入 `%APPDATA%/CogniNote/logs/desktop-backend.log`，Tauri 启动失败时显示错误弹窗。
- 第一版 Windows only；不做自动更新、代码签名、系统托盘、开机自启和跨平台发布。
- 公开安装包验收前必须加入桌面会话令牌或等价保护；PoC 阶段可先使用 `127.0.0.1 + 随机端口 + 不开放 CORS`。

## Implementation Changes

- 工具链前置检查：
  - JDK 固定使用 `D:\CodeApps\Java-JDK\jdk-25.0.2`，脚本优先调用 `$env:JAVA_HOME\bin\jpackage.exe`，不假设 `jpackage` 已在 PATH。
  - Node 使用当前项目基线 `20.19.6`。
  - Rust/Cargo 通过 `rustup` 安装 stable toolchain；Windows 需准备 MSVC Build Tools 与 WebView2 Runtime。
- 前端工程：
  - `package.json` 增加 Tauri CLI dev dependency 和脚本：`tauri`、`desktop:dev`、`desktop:build`。
  - `src-tauri/tauri.conf.json` 配置应用名、窗口、bundle resources、WebView2 安装策略。
  - `frontendDist` 指向 Vue `dist`，但第一版运行窗口加载 Spring Boot URL。
- 后端打包：
  - 继续执行 `mvn -Pwith-frontend package`，让 Spring Boot jar 内包含 Vue dist。
  - 先把最终 Spring Boot fat jar 复制到 `target/desktop/jpackage-input/`，再执行 `jpackage --type app-image --name CogniNoteBackend --input target/desktop/jpackage-input --main-jar cogninote-agent-design-0.0.1-SNAPSHOT.jar --dest target/desktop/backend`。
  - Tauri 打包时携带 `target/desktop/backend/CogniNoteBackend/` 整个目录。
- Tauri 主进程：
  - 用 Rust 管理一个 `BackendProcess` 状态，保存 child handle、端口、日志路径。
  - 后端启动超时、端口冲突、资源缺失、健康检查失败时给用户明确错误，不显示白屏。
  - 窗口关闭和应用退出时终止 child process。
  - 后续加父进程 PID 心跳，处理 Tauri 异常退出后的孤儿后端。
- 后端最小改动：
  - 保留 `server.address=127.0.0.1`、`COGNINOTE_PORT`、`/api/system/status`。
  - 不记录 API Key，不把数据写入安装目录，继续使用 `%APPDATA%/CogniNote/`。
- 文档同步：
  - 更新 README 和主设计文档，说明桌面启动、数据目录、日志目录、工具链、打包命令和当前 API Key 明文风险。
  - 保留 Phase 6 计划文件用于后续溯源。

## Test Plan

- 构建验证：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
mvn -Pwith-frontend package
.\scripts\build-desktop-backend.ps1
npm --prefix cogniNote-agent-front run desktop:build
```

- 桌面手测：
  - 双击应用能打开桌面窗口，不打开系统浏览器。
  - `18080` 被占用时能自动使用下一个端口。
  - `/api/system/status`、文档导入、搜索、模型配置、RAG SSE 对话都可用。
  - 关闭窗口后后端进程退出。
  - 后端启动失败时能看到明确错误和日志路径。
- 打包验收：
  - 在无系统 JDK 的 Windows 机器或虚拟机上运行。
  - 路径包含中文和空格时可运行。
  - WebView2 缺失或过旧时有明确处理。
  - 安装、开始菜单快捷方式、桌面快捷方式、卸载流程可用。
  - 卸载不误删 `%APPDATA%/CogniNote/` 用户数据。

## Assumptions

- 第六阶段只交付 Windows 桌面版。
- 后端基线继续使用 JDK 25。
- Rust/MSVC/WebView2 属于桌面打包工具链前置条件。
- Vue 页面继续由 Spring Boot 托管，前端 `/api` 相对路径不改。
- SQLite、Lucene、模型配置、RAG 业务逻辑继续全部留在 Java 后端。
- API Key 加密存储不是 Phase 6 主任务，但公开安装包验收前必须至少完成桌面会话令牌保护。
