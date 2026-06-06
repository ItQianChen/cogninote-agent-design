# CogniNote Agent 第十五阶段计划：Windows/macOS 普通用户安装与 0.1.0 分发链路

## Summary

第十五阶段把桌面打包从内部测试升级为可测试、可分发的 `0.1.0` 桌面链路。Windows 和 macOS 继续分开维护：Windows 使用 `tauri.conf.json`、PowerShell 脚本、`target/desktop/backend/CogniNoteBackend/` 和 NSIS；macOS 使用 `tauri.macos.conf.json`、Shell 脚本、`target/desktop-macos/backend/CogniNoteBackend.app/` 和 `.app` / `.dmg`。

`0.1.0` 的 CI 支持双模式：没有证书时生成 unsigned 测试包；配置完整证书后生成 signed 分发包。macOS signed 包通过 Developer ID 签名、公证和 staple 避免 Gatekeeper 把下载包识别为“已损坏”；Windows signed 包通过 Authenticode 签名和时间戳降低安装拦截风险。第一版仍只支持 macOS Apple Silicon，不做自动更新、Universal Binary、Intel Mac、App Store、MSIX/MSI。

## Key Changes

- 版本统一到 `0.1.0`：
  - Maven、前端 package、Tauri/Cargo 和双平台 Tauri 配置保持同一分发版本。
  - Maven 使用稳定最终 Jar 名 `target/cogninote-agent-design.jar`，打包脚本不再硬编码 `0.0.1-SNAPSHOT`。
  - `0.1.x` 路线固定为安装体验修补；自动更新、Universal 和更完整发布体验放到后续阶段。
- macOS 分发：
  - `tauri.macos.conf.json` 保持为 macOS 唯一 bundle 配置，只引用 `.icns` 图标和 macOS 后端 app-image。
  - 新增 hardened runtime entitlements，满足 Tauri/WebView 和本地后端进程运行需要。
  - GitHub Actions 未配置证书时构建 unsigned `.app` 和 `.dmg` 测试包。
  - GitHub Actions 配置完整 Secrets 后导入 Developer ID Application 证书到临时 keychain，构建签名 `.app` 和 `.dmg`。
  - signed 模式对 `.app` 和 `.dmg` 分别执行 codesign 验证、公证、staple、Gatekeeper 验证，并上传公证日志。
- Windows 分发：
  - `tauri.conf.json` 保持为 Windows 唯一 bundle 配置，只引用 `.ico` 图标和 Windows 后端 app-image。
  - 新增 `scripts/sign-windows-artifact.ps1` 作为 Tauri `signCommand` 入口。
  - 本地无证书时允许未签名构建。
  - GitHub Actions 未配置证书时构建 unsigned exe 与 NSIS 测试包。
  - GitHub Actions 配置完整 PFX Secret 后使用 `signtool` 签名 release exe 与 NSIS 安装包，并用 `Get-AuthenticodeSignature` 验证。
- CI 和 Secrets：
  - macOS workflow 继续手动触发，产物命名区分 `unsigned` 和 `signed`。
  - Windows workflow 继续手动触发，产物命名区分 `unsigned` 和 `signed`。
  - 两个 workflow 保留 `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`，避免 GitHub Actions Node 20 退役警告。

## Required GitHub Secrets

macOS：

```text
MACOS_CERTIFICATE_P12_BASE64
MACOS_CERTIFICATE_PASSWORD
MACOS_SIGNING_IDENTITY
APPLE_ID
APPLE_APP_SPECIFIC_PASSWORD
APPLE_TEAM_ID
APPLE_PROVIDER_SHORT_NAME
```

`APPLE_PROVIDER_SHORT_NAME` 只在 Apple 账号存在多个 provider 时需要；其它 macOS Secrets 是 signed 分发构建必需项。全部为空时 CI 会生成 unsigned 测试包，配置不完整时 CI 会失败。

Windows：

```text
WINDOWS_CERTIFICATE_PFX_BASE64
WINDOWS_CERTIFICATE_PASSWORD
```

证书和密码只进入 GitHub Secrets，不写入仓库、不打印到日志。Windows 两个 Secrets 全部为空时 CI 会生成 unsigned 测试包；只配置其中一个时 CI 会失败。

## Test Plan

- Windows 本地回归：
  - `.\scripts\build-desktop-app.ps1 -SkipTests`
  - 无证书时仍可生成未签名开发包。
  - 后端资源仍来自 `target/desktop/backend/CogniNoteBackend/`，日志仍写入 `%APPDATA%\CogniNote\logs\`。
- Windows CI 分发：
  - 手动触发 `Desktop Windows` workflow。
  - 无证书时 artifacts 包含 `0.1.0`、`windows-x64` 和 `unsigned` 命名。
  - 有证书时 artifacts 包含 `0.1.0`、`windows-x64` 和 `signed` 命名，release exe 与 NSIS installer 的 Authenticode 签名状态为 `Valid`。
- macOS CI 分发：
  - 手动触发 `Desktop macOS` workflow。
  - 无证书时 artifacts 包含 `0.1.0`、`macos-arm64` 和 `unsigned` 命名。
  - 有证书时 artifacts 包含 `0.1.0`、`macos-arm64` 和 `signed` 命名，`codesign`、`notarytool`、`stapler`、`spctl` 验证通过。
  - Apple Silicon 用户下载 signed DMG 后可拖入 Applications 并直接打开。
- 功能冒烟：
  - 文档导入、搜索、模型配置、RAG 对话、纯模型对话可用。
  - 关闭窗口后后端进程退出。
  - 打包版本、安装包名称和文档均显示 `0.1.0`。

## Assumptions

- macOS 第一版只支持 Apple Silicon arm64。
- 普通用户无感安装需要 Apple Developer Program 的 Developer ID Application 证书和 app-specific password。
- Windows signed 分发需要 Windows 代码签名 PFX 证书；无证书时只能产出 unsigned 测试包。
- 本阶段不改 Java/Spring Boot 业务、Vue 页面功能、REST API、SQLite/Lucene 数据结构。
