# CogniNote 桌面构建与运行指南

本指南说明如何构建 CogniNote 桌面应用。Windows 和 macOS 打包链路分开维护：Windows 继续使用 PowerShell、`tauri.conf.json` 和 NSIS；macOS 使用独立 Shell 脚本、`tauri.macos.conf.json` 和 `.app` / `.dmg`。

## 发布版本号更新

发布前不要对仓库做全局字符串替换。`package-lock.json` 和 `Cargo.lock` 里有大量第三方依赖版本，全局替换会把不存在的依赖版本写进锁文件，导致 `npm ci` 或 `cargo metadata` 失败。

使用白名单脚本更新项目自身版本：

```powershell
.\scripts\update-release-version.ps1 0.1.33
```

脚本会更新 Maven、前端 package、Tauri、Cargo、桌面 GitHub Actions 和主要分发文档中的项目版本，并保护 `powershell-utils`、`windows-threading`、`vswhom` 等第三方锁文件版本不被误改。预览改动可先运行：

```powershell
.\scripts\update-release-version.ps1 0.1.33 -WhatIf
```

脚本默认要求工作区干净；如果确实要在已有改动上预览或更新，可加 `-AllowDirty`。

## Windows 产物说明

第六阶段的桌面交付由两部分组成：

- Tauri 桌面壳：负责窗口、后端进程生命周期、启动失败提示。
- Spring Boot 后端 app-image：由 `jpackage` 生成，包含后端 exe、`app/` 和 `runtime/` 目录。

最终给用户运行或安装的是：

```text
cogniNote-agent-front/src-tauri/target/release/cogninote-agent.exe
cogniNote-agent-front/src-tauri/target/release/bundle/nsis/CogniNote_0.1.33_x64-setup.exe
```

`target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 只是后端启动器，不是最终入口。直接双击它不会打开桌面界面，也可能因为端口冲突或运行目录不完整而失败。

## macOS 产物说明

第十四阶段新增 macOS Apple Silicon 独立打包链路。macOS 后端 app-image、Tauri 配置和构建脚本都不复用 Windows 产物。

最终给用户运行或安装的是：

```text
cogniNote-agent-front/src-tauri/target/release/bundle/macos/CogniNote.app
cogniNote-agent-front/src-tauri/target/release/bundle/dmg/CogniNote_0.1.33_aarch64.dmg
```

`target/desktop-macos/backend/CogniNoteBackend.app` 只是 macOS 后端 app-image，不是最终入口。本地脚本默认生成未签名开发包；GitHub Actions 未配置证书时也会生成 unsigned 测试包。普通用户分发请使用 GitHub Actions 签名、公证后的 `.dmg` 和 `.app.zip`。

## Windows 前置工具链

Windows 桌面打包需要：

- JDK 25。本机默认使用 `D:\CodeApps\Java-JDK\jdk-25.0.2`；若设置了 `JAVA_HOME`，脚本会优先使用 `JAVA_HOME`。
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

GitHub Actions 中不需要写死本机 JDK 路径。`desktop-windows.yml` 使用 `actions/setup-java` 安装 JDK 25 并设置 `JAVA_HOME`，Windows `.ps1` 脚本会自动读取该环境变量。

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

脚本会在 Maven 打包前删除 `target/classes/static`。Maven `package` 不是 clean 构建，如果旧 Vite hash 资源残留在该目录，新的桌面后端 jar 可能仍包含旧前端文件。

```text
scripts/build-desktop-app.ps1
```

完整桌面构建入口。它会检查工具链、构建后端 app-image，然后执行 `npm --prefix cogniNote-agent-front run desktop:build` 生成 Tauri release exe 和 NSIS 安装包。

Windows Tauri 配置默认不包含 `signCommand`，这样 unsigned 构建不会触发 Tauri 签名阶段。只有 `COGNINOTE_REQUIRE_WINDOWS_SIGNING=true` 时，脚本才会在构建期间临时注入 `signCommand`，构建结束后恢复配置。GitHub Actions 也支持双模式：两个 Secret 都存在时强制签名并验证；两个 Secret 都为空时继续上传 unsigned 测试包；只配置其中一个会直接失败，避免误发半配置包。

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

脚本同样会在 Maven 打包前删除 `target/classes/static`，避免 macOS 连续打包时把旧前端 hash 资源带进新 jar。

```text
scripts/build-desktop-app-macos.sh
```

完整 macOS 桌面构建入口。它会检查工具链、构建 macOS 后端 app-image，临时切换 Tauri active config，清理旧 `.app` / `.dmg` 输出，然后执行 `npm --prefix cogniNote-agent-front run desktop:build:macos` 生成 `.app` 和 `.dmg`。不要用 Windows `tauri.conf.json` 直接打 macOS 包。

签名构建入口只给 CI 使用：

```bash
bash ./scripts/build-desktop-app-macos.sh --skip-tests --sign
```

该模式要求 `APPLE_SIGNING_IDENTITY` 已设置。脚本会把签名 identity 只写入临时 active Tauri 配置，构建结束后恢复 Windows `tauri.conf.json`。macOS 最终包内还嵌入了 `jpackage` 生成的 `CogniNoteBackend.app`，signed 模式会先用同一份 Developer ID 证书签名这个嵌套后端 app，再让 Tauri 生成外层桌面 app 和 DMG，并校验外层 app 与嵌套后端 app 的签名。不要把未签名的后端 app 直接塞进 signed 外层包，否则用户下载后 Gatekeeper 可能仍提示“已损坏，无法打开”。

## GitHub Actions 构建

Windows 和 macOS CI 也分开维护，均默认手动触发：

```text
.github/workflows/desktop-windows.yml
.github/workflows/desktop-macos.yml
```

Windows workflow 使用 `windows-latest`，通过 `actions/setup-java` 设置 JDK 25，通过 `actions/setup-node` 设置 Node，通过 `dtolnay/rust-toolchain` 设置 Rust，然后执行：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

签名分发构建需要 GitHub Secrets：

```text
WINDOWS_CERTIFICATE_PFX_BASE64
WINDOWS_CERTIFICATE_PASSWORD
```

如果不配置这两个 Secrets，workflow 仍会构建并上传 unsigned 测试包。unsigned Windows 包可以安装，但普通用户可能看到 SmartScreen 或 Unknown Publisher 提示，需要点击“更多信息 -> 仍要运行”。如果只配置其中一个 Secret，workflow 会失败。

Windows artifacts：

```text
CogniNote-0.1.33-windows-x64-unsigned-portable
CogniNote-0.1.33-windows-x64-unsigned-installer
CogniNote-0.1.33-windows-x64-signed-portable
CogniNote-0.1.33-windows-x64-signed-installer
```

macOS workflow 使用 Apple Silicon runner。未配置签名 Secrets 时执行：

```bash
bash ./scripts/build-desktop-app-macos.sh --skip-tests
```

配置完整签名和公证 Secrets 后导入 Developer ID Application 证书，并执行：

```bash
bash ./scripts/build-desktop-app-macos.sh --skip-tests --sign
```

签名分发构建需要 GitHub Secrets：

```text
MACOS_CERTIFICATE_P12_BASE64
MACOS_CERTIFICATE_PASSWORD
MACOS_SIGNING_IDENTITY
APPLE_ID
APPLE_APP_SPECIFIC_PASSWORD
APPLE_TEAM_ID
APPLE_PROVIDER_SHORT_NAME
```

`APPLE_PROVIDER_SHORT_NAME` 仅在 Apple 账号存在多个 provider 时需要。除了 `APPLE_PROVIDER_SHORT_NAME`，其它 macOS Secrets 必须全部配置才会进入 signed 模式；全部为空时进入 unsigned 测试模式；配置不完整会失败。signed 模式会对 `.app` 和 `.dmg` 分别执行 `codesign`、`notarytool`、`stapler` 和 `spctl` 验证，并上传公证日志。

unsigned macOS 包仅用于技术测试。即使从 GitHub Release、浏览器或微信下载，macOS 也会给文件附加 quarantine；Gatekeeper 最终检查的是挂载后或复制后的 `.app`，不是只检查 `.dmg`。因此只对 DMG 执行 `xattr` 不保证能运行，普通用户分发必须使用 signed、notarized、stapled DMG。signed workflow 会先公证并 staple 外层 `.app`，再用这个已 staple 的 `.app` 重新生成、签名、公证并 staple 发布用 DMG，确保用户下载的 DMG 内部就是通过 Gatekeeper 校验的 app。

macOS artifacts：

```text
CogniNote-0.1.33-macos-arm64-unsigned-app
CogniNote-0.1.33-macos-arm64-unsigned-dmg
CogniNote-0.1.33-macos-arm64-signed-app
CogniNote-0.1.33-macos-arm64-signed-dmg
CogniNote-0.1.33-macos-notarization-logs
```

两个 workflow 不共享 Tauri bundle 配置，不共享后端 app-image 输出目录，也不把平台差异塞进同一个脚本。

### 发布到 GitHub Release

两个 workflow 默认只上传 Actions artifacts，不会自动污染 Release 页面。手动触发 workflow 时可以设置：

```text
publish_release = true
release_tag = v0.1.33-test.1
```

`release_tag` 留空时，unsigned 构建默认发布到 `v0.1.33-test.1`，signed 构建默认发布到 `v0.1.33`。unsigned Release 会标记为 pre-release。Windows 和 macOS 可以分别运行 workflow，并使用同一个 `release_tag`，后运行的平台会把自己的资产追加到同一个 Release 中。

Release 上传的是真实安装文件，不是 Actions artifact 外层 zip。普通用户优先下载 signed 资产；unsigned 资产只给开发者做技术测试：

```text
CogniNote-0.1.33-windows-x64-unsigned-installer.exe
CogniNote-0.1.33-windows-x64-unsigned-portable.zip
CogniNote-0.1.33-macos-arm64-signed.dmg
CogniNote-0.1.33-macos-arm64-signed.app.zip
```

给测试用户分发时，优先发送 Release 页面里的 `.exe` 或 signed `.dmg` 下载链接。macOS unsigned DMG 不适合普通用户分发。

## Windows 运行和验收

开发态直接运行：

```powershell
.\cogniNote-agent-front\src-tauri\target\release\cogninote-agent.exe
```

模拟用户安装：

```powershell
.\cogniNote-agent-front\src-tauri\target\release\bundle\nsis\CogniNote_0.1.33_x64-setup.exe
```

正常行为：

- 双击后打开 CogniNote 桌面窗口，不打开系统浏览器。
- 后端在后台启动，不应弹出常驻 cmd 窗口。
- Tauri 会在 `18080-18120` 中选择可用端口，并把端口通过 `COGNINOTE_PORT` 注入后端。
- 桌面窗口加载 `http://127.0.0.1:{port}/`，前端 `/api` 相对路径继续同源工作。
- 关闭窗口后，Tauri 会终止后端进程。
- 第二次启动应用时会聚焦已有窗口，避免旧实例仍运行时误以为新版已启动。
- 安装器会在升级、降级重装或卸载前尝试关闭 `CogniNote.exe`、`cogninote-agent.exe` 和 `CogniNoteBackend.exe`。
- 安装前会清理旧安装目录里的主程序和 `backend/`；如果旧文件仍被占用，安装会中止，避免前端或后端资源停留在旧版本。
- 安装前和卸载后会清理 `%LOCALAPPDATA%\com.itqianchen.cogninote\EBWebView` 下的 HTTP、Code Cache、GPU、Service Worker 和 CacheStorage 等缓存目录，避免 WebView2 继续加载旧 `index.html` 或旧 Vite chunk。该清理不删除 `%APPDATA%\CogniNote` 业务数据，也不清理 WebView localStorage。
- 卸载后会清理常见桌面和开始菜单快捷方式残留，但不会删除 `%APPDATA%\CogniNote` 用户数据。

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

如需彻底清理测试数据，请先确认不再需要知识库、聊天记录和模型配置，再手动删除：

```powershell
Remove-Item -LiteralPath "$env:APPDATA\CogniNote" -Recurse -Force
```

## macOS 运行和验收

开发态直接运行：

```bash
open ./cogniNote-agent-front/src-tauri/target/release/bundle/macos/CogniNote.app
```

模拟用户安装：

```bash
open ./cogniNote-agent-front/src-tauri/target/release/bundle/dmg/CogniNote_0.1.33_aarch64.dmg
```

正常行为：

- 双击后打开 CogniNote 桌面窗口，不打开系统浏览器。
- 后端在后台启动，不应弹出 Terminal 窗口。
- Tauri 会在 `18080-18120` 中选择可用端口，并把端口通过 `COGNINOTE_PORT` 注入后端。
- 桌面窗口加载 `http://127.0.0.1:{port}/`，前端 `/api` 相对路径继续同源工作。
- 关闭窗口后，Tauri 会终止后端进程。
- 升级或降级后首次启动时，桌面壳会比较 `~/Library/Application Support/CogniNote/desktop-webview-version.txt` 与当前桌面壳版本；版本变化时清理已知 WKWebView 缓存目录，macOS 14+ 还会使用版本相关的 `data_store_identifier` 隔离 WebView 数据仓库，再加载 `http://127.0.0.1:{port}/`。

日志路径：

```text
~/Library/Application Support/CogniNote/logs/desktop-backend.log
~/Library/Application Support/CogniNote/logs/app.log
```

数据目录：

```text
~/Library/Application Support/CogniNote/
```

本地脚本默认产物未签名、未公证，Gatekeeper 可能拦截运行。普通用户安装请使用 GitHub Actions 签名、公证后的 DMG；下载后应可直接拖入 Applications 并打开。signed 产物必须同时覆盖嵌套的 `CogniNoteBackend.app`、外层 `CogniNote.app` 和发布用 DMG，不能只签外层 Tauri app。

macOS 升级或降级时请先完全退出旧版，再把 DMG 内的 `CogniNote.app` 拖入 `/Applications` 并选择替换旧版本，不要直接从 DMG 挂载目录运行。不要用 `cp -R` 覆盖已有 `.app`，因为 `.app` 是目录包，命令行合并覆盖可能留下旧的主程序或嵌套后端资源；脚本安装时应先删除旧包再复制：

```bash
rm -rf /Applications/CogniNote.app
ditto "/Volumes/CogniNote/CogniNote.app" /Applications/CogniNote.app
```

若是 unsigned 技术测试包，拖入 `/Applications` 后再清理 app 的 quarantine：

```bash
sudo xattr -dr com.apple.quarantine /Applications/CogniNote.app
open /Applications/CogniNote.app
```

如果 signed DMG 仍提示“已损坏，无法打开”，优先检查 workflow 的 `CogniNote-0.1.33-macos-notarization-logs`、`spctl` 输出，以及 `desktop-backend.log` 中的实际 app 路径。重点确认 `CogniNote.app/Contents/Resources/backend/CogniNoteBackend.app` 已用同一 Developer ID 证书签名，且发布用 DMG 是由已 staple 的 `CogniNote.app` 重新生成的。

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

### Windows 覆盖安装后仍显示旧版本

Windows 安装目录必须被当成一个完整版本快照处理。NSIS 默认复制文件时可以覆盖已有文件，但旧进程锁文件、降级安装或手动选择“不卸载继续安装”都可能留下旧的前端主程序或后端 app-image。项目的 NSIS hook 会在复制新文件前关闭旧进程并删除旧主程序和 `backend/`，删除失败时直接中止安装，避免出现“安装完成但实际仍运行旧版本”。

如果确认安装目录、后端 jar 和 `target/classes/static` 已经是新版本，但前端页面仍显示旧版本，优先检查 WebView2 缓存。Tauri WebView2 的缓存位于：

```text
%LOCALAPPDATA%\com.itqianchen.cogninote\EBWebView\
```

正常安装器会自动清理其中的 `Default\Cache`、`Default\Code Cache`、`Default\GPUCache`、`Default\Service Worker`、`Default\CacheStorage` 等缓存目录；Geek 等卸载工具之所以能让问题“恢复正常”，通常是因为它额外清理了这些浏览器缓存，而不是因为业务数据目录有问题。

验证降级场景时，应从新版本安装包进入维护页并选择“先卸载再安装”，或先通过 Windows 应用卸载旧版后再安装旧版。不要用“保留旧安装目录直接覆盖”来判断降级是否成功。

### 桌面前端静态资源仍是旧版本

桌面版前端资源经过两层缓存路径：构建时由 Maven profile 把 `cogniNote-agent-front/dist` 复制进 `target/classes/static`，运行时再由 WebView 缓存 HTTP 响应和 JS 字节码。因此排查旧页面时按顺序确认：

1. 打包脚本是否在 `mvn -Pwith-frontend package` 前删除了 `target/classes/static`。
2. 新安装目录中的后端 jar 是否包含最新 `BOOT-INF/classes/static/index.html` 和最新 hash 资源。
3. Spring Boot 是否对 `/`、`/index.html`、`/assets/**` 和 SPA 路由返回 `Cache-Control: no-store, no-cache, max-age=0, must-revalidate`。
4. Windows WebView2 或 macOS WKWebView 是否已经按当前版本清理或隔离缓存。

这四层都通过后，再看系统设置页的后端版本、前端版本和桌面壳版本，判断用户实际启动的是哪个版本。

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

`CogniNoteBackend.exe` 是后端 app-image 的一部分，负责启动 Spring Boot 服务。它不是桌面应用入口。请运行 `cogninote-agent.exe` 或安装 `CogniNote_0.1.33_x64-setup.exe`。

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

如果升级或降级后仍显示旧版本，先确认旧版已完全退出。macOS 的 single-instance 机制会把第二次启动转交给正在运行的旧实例，桌面壳会弹窗提示先退出旧版；退出后从 `/Applications/CogniNote.app` 重新打开。

macOS 的 `.dmg` 拖拽安装过程不会执行 CogniNote 代码，因此不能像 Windows NSIS 一样在安装阶段清理 WKWebView 缓存。项目把 macOS 缓存处理放在启动阶段：版本变化时写入 `desktop-webview-version.txt`，清理 `~/Library/Caches`、`~/Library/WebKit` 和沙盒容器下已知的 CogniNote WebView 缓存路径；macOS 14+ 通过 Tauri `data_store_identifier` 使用版本相关的数据仓库，避免旧版本缓存继续影响新版本。业务数据仍保存在 `~/Library/Application Support/CogniNote/`，不会随缓存清理删除。

如果本地未签名 `.app` 被 Gatekeeper 拦截，可在系统设置中允许运行，仅用于开发验证。若 GitHub Actions 分发产物仍提示“已损坏，无法打开”，通常说明嵌套后端 app 签名、公证或 staple 没成功，或发布用 DMG 不是由已 staple 的 `CogniNote.app` 重新生成。先检查 `CogniNote-0.1.33-macos-notarization-logs` artifact、workflow 中的 `spctl` 输出，以及嵌套路径 `CogniNote.app/Contents/Resources/backend/CogniNoteBackend.app` 的签名状态。

### VS Code 中 lib.rs 提示 OUT_DIR 不存在

症状：`cogniNote-agent-front/src-tauri/src/lib.rs` 的 `tauri::generate_context!()` 附近提示 `OUT_DIR env var is not set, do you have a build script?`。

这是 rust-analyzer 没有通过 Cargo 加载 Tauri 子工程导致的 IDE 诊断，不是 `lib.rs` 的运行时错误。`generate_context!()` 依赖 `src-tauri/build.rs` 执行后提供的 `OUT_DIR`，必须让 rust-analyzer 从 `cogniNote-agent-front/src-tauri/Cargo.toml` 加载项目，并且本机 PATH 中能找到 `cargo`。

项目已在根目录和前端目录的 VS Code settings 中配置 Tauri Cargo 项目。若仍看到该提示，请先确认 Rust/Cargo 已安装并加入 PATH，然后在 VS Code 执行 `Rust Analyzer: Restart Server` 或重新打开工作区。
