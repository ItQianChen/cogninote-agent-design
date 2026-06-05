# CogniNote 桌面构建与运行指南

本指南说明如何构建 CogniNote 桌面应用。Windows 和 macOS 打包链路分开维护：Windows 继续使用 PowerShell、`tauri.conf.json` 和 NSIS；macOS 使用独立 Shell 脚本、`tauri.macos.conf.json` 和 `.app` / `.dmg`。

## Windows 产物说明

第六阶段的桌面交付由两部分组成：

- Tauri 桌面壳：负责窗口、后端进程生命周期、启动失败提示。
- Spring Boot 后端 app-image：由 `jpackage` 生成，包含后端 exe、`app/` 和 `runtime/` 目录。

最终给用户运行或安装的是：

```text
cogniNote-agent-front/src-tauri/target/release/cogninote-agent.exe
cogniNote-agent-front/src-tauri/target/release/bundle/nsis/CogniNote_0.0.1_x64-setup.exe
```

`target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 只是后端启动器，不是最终入口。直接双击它不会打开桌面界面，也可能因为端口冲突或运行目录不完整而失败。

## macOS 产物说明

第十四阶段新增 macOS Apple Silicon 独立打包链路。macOS 后端 app-image、Tauri 配置和构建脚本都不复用 Windows 产物。

最终给用户运行或安装的是：

```text
cogniNote-agent-front/src-tauri/target/release/bundle/macos/CogniNote.app
cogniNote-agent-front/src-tauri/target/release/bundle/dmg/CogniNote_0.0.1_aarch64.dmg
```

`target/desktop-macos/backend/CogniNoteBackend.app` 只是 macOS 后端 app-image，不是最终入口。第一版 macOS 产物未签名、未公证，只用于 Apple Silicon 本机验证和内部测试。

## Windows 前置工具链

Windows 桌面打包需要：

- JDK 25，项目脚本默认使用 `D:\CodeApps\Java-JDK\jdk-25.0.2`。
- Maven 3.9+。
- Node.js 20.19.6 或兼容版本。
- npm 10.8.2 或兼容版本。
- Rust stable toolchain 和 Cargo。
- MSVC Build Tools。
- WebView2 Runtime。

先在项目根目录运行工具链检查：

```powershell
cd D:\code\JavaCode\cogninote-agent-design
.\scripts\verify-desktop-toolchain.ps1
```

如果本机 JDK 不在默认路径，可传入参数：

```powershell
.\scripts\verify-desktop-toolchain.ps1 -JdkHome 'D:\CodeApps\Java-JDK\jdk-25.0.2'
```

## macOS 前置工具链

macOS 第一版只支持 Apple Silicon arm64。需要：

- JDK 25 arm64，设置 `JDK_HOME` 或 `JAVA_HOME` 指向完整 JDK。
- Maven 3.9+。
- Node.js 20.19+ 或兼容版本。
- npm。
- Rust stable toolchain 和 Cargo。
- Xcode Command Line Tools。

先在项目根目录运行工具链检查：

```bash
bash ./scripts/verify-desktop-toolchain-macos.sh
```

如果 JDK 不在默认位置，可传入环境变量：

```bash
JDK_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  bash ./scripts/verify-desktop-toolchain-macos.sh
```

## Windows 构建脚本

`.ps1` 是 PowerShell 脚本，不建议双击运行。请打开 PowerShell，进入项目根目录后执行。

完整桌面打包：

```powershell
cd D:\code\JavaCode\cogninote-agent-design
.\scripts\build-desktop-app.ps1
```

跳过测试的快速打包：

```powershell
cd D:\code\JavaCode\cogninote-agent-design
.\scripts\build-desktop-app.ps1 -SkipTests
```

如果 PowerShell 提示不允许运行脚本，可只在当前窗口临时放开执行策略：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\build-desktop-app.ps1 -SkipTests
```

也可以用单行命令绕过当前窗口策略：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-desktop-app.ps1 -SkipTests
```

### Windows 脚本分工

```text
scripts/verify-desktop-toolchain.ps1
```

检查 JDK、`jpackage`、Node、npm、Rust、Cargo、MSVC `cl/link` 是否可用。脚本会尝试把 `%USERPROFILE%\.cargo\bin` 和 Visual Studio Build Tools 环境注入当前 PowerShell 进程。

```text
scripts/build-desktop-backend.ps1
```

先执行 `mvn -Pwith-frontend package`，再把最终 Spring Boot fat jar 复制到 `target/desktop/jpackage-input/`，最后生成后端 app-image：

```text
target/desktop/backend/CogniNoteBackend/
```

```text
scripts/build-desktop-app.ps1
```

完整桌面构建入口。它会检查工具链、构建后端 app-image，然后执行 `npm --prefix cogniNote-agent-front run desktop:build` 生成 Tauri release exe 和 NSIS 安装包。

## macOS 构建脚本

macOS 脚本使用 Bash 运行。为避免 Windows 和 macOS 混在一起，macOS 后端产物固定写入 `target/desktop-macos/`。完整打包入口会在构建期间临时把 active Tauri 配置切换为 `src-tauri/tauri.macos.conf.json`，构建结束后恢复 Windows `tauri.conf.json`。

完整桌面打包：

```bash
cd /path/to/cogninote-agent-design
bash ./scripts/build-desktop-app-macos.sh
```

跳过测试的快速打包：

```bash
cd /path/to/cogninote-agent-design
bash ./scripts/build-desktop-app-macos.sh --skip-tests
```

### macOS 脚本分工

```text
scripts/verify-desktop-toolchain-macos.sh
```

检查 Darwin、arm64、JDK 25、`jpackage`、Maven、Node、npm、Rust、Cargo 和 Xcode Command Line Tools。

```text
scripts/build-desktop-backend-macos.sh
```

先执行 `mvn -Pwith-frontend package`，再把最终 Spring Boot fat jar 复制到 `target/desktop-macos/jpackage-input/`，最后生成后端 app-image：

```text
target/desktop-macos/backend/CogniNoteBackend.app/
```

```text
scripts/build-desktop-app-macos.sh
```

完整 macOS 桌面构建入口。它会检查工具链、构建 macOS 后端 app-image，临时切换 Tauri active config，然后执行 `npm --prefix cogniNote-agent-front run desktop:build:macos` 生成 `.app` 和 `.dmg`。不要用 Windows `tauri.conf.json` 直接打 macOS 包。

## Windows 运行和验收

开发态直接运行：

```powershell
.\cogniNote-agent-front\src-tauri\target\release\cogninote-agent.exe
```

模拟用户安装：

```powershell
.\cogniNote-agent-front\src-tauri\target\release\bundle\nsis\CogniNote_0.0.1_x64-setup.exe
```

正常行为：

- 双击后打开 CogniNote 桌面窗口，不打开系统浏览器。
- 后端在后台启动，不应弹出常驻 cmd 窗口。
- Tauri 会在 `18080-18120` 中选择可用端口，并把端口通过 `COGNINOTE_PORT` 注入后端。
- 桌面窗口加载 `http://127.0.0.1:{port}/`，前端 `/api` 相对路径继续同源工作。
- 关闭窗口后，Tauri 会终止后端进程。

日志路径：

```text
%APPDATA%\CogniNote\logs\desktop-backend.log
%APPDATA%\CogniNote\logs\app.log
```

`desktop-backend.log` 记录 Tauri 启动后端进程时的输出，`app.log` 记录 Spring Boot 业务日志。启动失败先看前者，接口、模型调用、索引和 RAG 问题先看后者。

如需临时把 Spring Boot 业务日志写到其它位置，可在启动后端前设置：

```powershell
$env:COGNINOTE_LOG_FILE='D:\temp\cogninote-app.log'
```

数据目录仍然是：

```text
%APPDATA%\CogniNote\
```

卸载桌面应用不应删除该用户数据目录。

## macOS 运行和验收

开发态直接运行：

```bash
open ./cogniNote-agent-front/src-tauri/target/release/bundle/macos/CogniNote.app
```

模拟用户安装：

```bash
open ./cogniNote-agent-front/src-tauri/target/release/bundle/dmg/CogniNote_0.0.1_aarch64.dmg
```

正常行为：

- 双击后打开 CogniNote 桌面窗口，不打开系统浏览器。
- 后端在后台启动，不应弹出 Terminal 窗口。
- Tauri 会在 `18080-18120` 中选择可用端口，并把端口通过 `COGNINOTE_PORT` 注入后端。
- 桌面窗口加载 `http://127.0.0.1:{port}/`，前端 `/api` 相对路径继续同源工作。
- 关闭窗口后，Tauri 会终止后端进程。

日志路径：

```text
~/Library/Application Support/CogniNote/logs/desktop-backend.log
~/Library/Application Support/CogniNote/logs/app.log
```

数据目录：

```text
~/Library/Application Support/CogniNote/
```

第一版 macOS 产物不签名、不公证。Gatekeeper 可能拦截运行，内部测试时可在系统设置里允许运行或使用本机开发环境直接验证。

## 图标更新

Windows Tauri 图标位于：

```text
cogniNote-agent-front/src-tauri/icons/icon.ico
```

替换 `icon.ico` 后，需要重新执行桌面打包脚本，新的 exe 和安装包才会带上图标：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

建议 `.ico` 内包含 `256/128/64/48/32/16` 多个尺寸，避免任务栏、开始菜单、安装器在不同缩放比例下模糊。

macOS 图标位于：

```text
cogniNote-agent-front/src-tauri/icons/icon.icns
```

Tauri 编译期默认窗口图标源位于：

```text
cogniNote-agent-front/src-tauri/icons/icon.png
```

`icon.png` 是 `tauri::generate_context!()` 在 macOS/Linux 编译期读取的运行时默认窗口图标源。macOS bundle 配置仍然只引用 `.icns`，Windows bundle 配置仍然只引用 `.ico`。不要把两个平台的 bundle 图标混进同一份 Tauri 配置，也不要删除 `icon.png`，否则 GitHub macOS 构建会在 Rust 编译阶段报 `failed to open icon ... icons/icon.png`。

## 常见问题

### Windows 脚本无法运行

症状：PowerShell 提示脚本被执行策略阻止。

处理：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\scripts\build-desktop-app.ps1 -SkipTests
```

### 构建时出现 os error 5 或拒绝访问

通常是旧的 `cogninote-agent.exe` 或 `CogniNoteBackend.exe` 还在运行，导致 Tauri release 资源被 Windows 锁住。

处理：

```powershell
Get-Process | Where-Object {
  $_.ProcessName -eq 'cogninote-agent' -or $_.ProcessName -eq 'CogniNoteBackend'
} | Stop-Process -Force -ErrorAction SilentlyContinue

Remove-Item -Recurse -Force .\cogniNote-agent-front\src-tauri\target\release\backend -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .\cogniNote-agent-front\src-tauri\target\release\bundle -ErrorAction SilentlyContinue
Remove-Item -Force .\cogniNote-agent-front\src-tauri\target\release\cogninote-agent.exe -ErrorAction SilentlyContinue
```

然后重新打包：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

### Windows 双击后没反应

先查看后端日志：

```text
%APPDATA%\CogniNote\logs\desktop-backend.log
```

再确认是否已有旧进程占用端口：

```powershell
Get-NetTCPConnection -LocalAddress 127.0.0.1 -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -ge 18080 -and $_.LocalPort -le 18120 }
```

### Windows 弹出 cmd 窗口

release 版本的 Tauri 主程序应使用 Windows GUI 子系统，后端子进程也应使用无控制台窗口方式启动。若仍弹出 cmd 窗口，请确认运行的是最新构建产物，并重新执行：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

### Windows 只运行了 CogniNoteBackend.exe

`CogniNoteBackend.exe` 是后端 app-image 的一部分，负责启动 Spring Boot 服务。它不是桌面应用入口。请运行 `cogninote-agent.exe` 或安装 `CogniNote_0.0.1_x64-setup.exe`。

### Windows 打包时 npm ci 提示 EPERM unlink

症状：`mvn -Pwith-frontend package` 阶段失败，日志包含：

```text
npm error code EPERM
npm error syscall unlink
npm error path ...\node_modules\@rolldown\...\rolldown-binding.win32-x64-msvc.node
```

这是 Windows 文件锁问题，不是 Java 测试失败。常见原因是正在运行的 Vite/dev server、上一次构建残留的 Node 进程、IDE 插件或杀毒软件占用了 Rolldown 原生 `.node` 文件。Windows 打包脚本会在 `npm ci` 前自动停止当前项目的 Vite dev server；如果仍然失败，再手动关闭正在运行的桌面应用和其它可能占用前端依赖的进程。

如需手动清理残留 Node 进程，可执行：

```powershell
Get-Process node -ErrorAction SilentlyContinue | Stop-Process
```

然后重新安装前端依赖并打包：

```powershell
npm --prefix cogniNote-agent-front ci
.\scripts\build-desktop-app.ps1 -SkipTests
```

Windows 构建脚本会检查 `mvn`、`npm` 和 `jpackage` 的退出码。若 `npm ci` 再次失败，脚本会停在真实失败点，不会继续生成半成品后端 app-image 或继续执行 Tauri 打包。

### macOS 脚本提示不是 arm64

第十四阶段只支持 Apple Silicon。请在 M 系列 Mac 上运行，或等后续阶段增加 Intel / Universal Binary 支持。

### macOS 找不到后端启动器

确认先运行了：

```bash
bash ./scripts/build-desktop-backend-macos.sh --skip-tests
```

并确认存在：

```text
target/desktop-macos/backend/CogniNoteBackend.app/Contents/MacOS/CogniNoteBackend
```

### macOS 双击后没反应

先查看：

```text
~/Library/Application Support/CogniNote/logs/desktop-backend.log
```

如果 `.app` 被 Gatekeeper 拦截，先在系统设置中允许运行。第一版内部测试不做签名和公证。

### VS Code 中 lib.rs 提示 OUT_DIR 不存在

症状：`cogniNote-agent-front/src-tauri/src/lib.rs` 的 `tauri::generate_context!()` 附近提示 `OUT_DIR env var is not set, do you have a build script?`。

这是 rust-analyzer 没有通过 Cargo 加载 Tauri 子工程导致的 IDE 诊断，不是 `lib.rs` 的运行时错误。`generate_context!()` 依赖 `src-tauri/build.rs` 执行后提供的 `OUT_DIR`，必须让 rust-analyzer 从 `cogniNote-agent-front/src-tauri/Cargo.toml` 加载项目，并且本机 PATH 中能找到 `cargo`。

项目已在根目录和前端目录的 VS Code settings 中配置 Tauri Cargo 项目。若仍看到该提示，请先确认 Rust/Cargo 已安装并加入 PATH，然后在 VS Code 执行 `Rust Analyzer: Restart Server` 或重新打开工作区。
