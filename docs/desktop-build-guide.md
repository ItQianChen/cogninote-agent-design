# CogniNote 桌面构建与运行指南

本指南说明如何在 Windows 上运行桌面构建脚本、生成安装包，以及如何区分最终桌面应用和后端 app-image。

## 产物说明

第六阶段的桌面交付由两部分组成：

- Tauri 桌面壳：负责窗口、后端进程生命周期、启动失败提示。
- Spring Boot 后端 app-image：由 `jpackage` 生成，包含后端 exe、`app/` 和 `runtime/` 目录。

最终给用户运行或安装的是：

```text
cogniNote-agent-front/src-tauri/target/release/cogninote-agent.exe
cogniNote-agent-front/src-tauri/target/release/bundle/nsis/CogniNote_0.0.1_x64-setup.exe
```

`target/desktop/backend/CogniNoteBackend/CogniNoteBackend.exe` 只是后端启动器，不是最终入口。直接双击它不会打开桌面界面，也可能因为端口冲突或运行目录不完整而失败。

## 前置工具链

桌面打包需要：

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

## 如何运行 ps1 脚本

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

## 构建脚本分工

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

## 运行和验收

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

后端日志路径：

```text
%APPDATA%\CogniNote\logs\desktop-backend.log
```

数据目录仍然是：

```text
%APPDATA%\CogniNote\
```

卸载桌面应用不应删除该用户数据目录。

## 图标更新

Tauri 图标位于：

```text
cogniNote-agent-front/src-tauri/icons/icon.ico
```

替换 `icon.ico` 后，需要重新执行桌面打包脚本，新的 exe 和安装包才会带上图标：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

建议 `.ico` 内包含 `256/128/64/48/32/16` 多个尺寸，避免任务栏、开始菜单、安装器在不同缩放比例下模糊。

## 常见问题

### 脚本无法运行

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

### 双击后没反应

先查看后端日志：

```text
%APPDATA%\CogniNote\logs\desktop-backend.log
```

再确认是否已有旧进程占用端口：

```powershell
Get-NetTCPConnection -LocalAddress 127.0.0.1 -ErrorAction SilentlyContinue |
  Where-Object { $_.LocalPort -ge 18080 -and $_.LocalPort -le 18120 }
```

### 弹出 cmd 窗口

release 版本的 Tauri 主程序应使用 Windows GUI 子系统，后端子进程也应使用无控制台窗口方式启动。若仍弹出 cmd 窗口，请确认运行的是最新构建产物，并重新执行：

```powershell
.\scripts\build-desktop-app.ps1 -SkipTests
```

### 只运行了 CogniNoteBackend.exe

`CogniNoteBackend.exe` 是后端 app-image 的一部分，负责启动 Spring Boot 服务。它不是桌面应用入口。请运行 `cogninote-agent.exe` 或安装 `CogniNote_0.0.1_x64-setup.exe`。
