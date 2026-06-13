# 第 27 阶段计划：桌面会话令牌保护与自动更新

## Summary

第 27 阶段补齐桌面本机 API 的轻量访问保护，并接入 Tauri 2 自动更新。桌面壳每次启动生成随机 session token，只把 token 传给同生命周期内的 Spring Boot 后端和 WebView 前端；后端在桌面模式下只保护 `/api/**`，静态页面、SPA 路由和 assets 继续无 token 加载，避免首屏被阻断。

自动更新采用 GitHub Release 静态 manifest，不引入动态更新服务器。前端设置页提供 `stable` / `preview` 通道选择，默认 `stable`；应用启动后自动静默检查一次，发现更新时提示用户确认下载、安装并重启。

## Goals

- Tauri 启动时生成 32 字节随机桌面会话 token，并通过 `COGNINOTE_DESKTOP_SESSION_TOKEN` 注入后端。
- Spring Boot 在 `COGNINOTE_DESKTOP=true` 时启用 `/api/**` token 过滤，缺失或错误返回 `401` 和统一 `ApiResponse`。
- 前端普通 JSON API 和聊天 SSE 请求统一带 `X-CogniNote-Desktop-Session`；普通浏览器开发模式不强制令牌。
- Rust 侧使用 `tauri-plugin-updater = 2.10.1`，通过自定义 Tauri commands 支持运行时选择 stable/preview endpoint。
- Windows updater 使用最终 NSIS installer；macOS updater 使用 `.app.tar.gz`，DMG 保留给手动下载。
- GitHub Actions 发布真实版本资产后，更新 `desktop-updater-stable` 或 `desktop-updater-preview` Release 中的 `latest.json`。

## Non-goals

- 不购买 Windows/macOS 付费代码签名证书。
- 不承诺消除 SmartScreen、Gatekeeper 或 unsigned 分发提示。
- 不维护动态更新服务器，不在运行时调用 GitHub REST API。
- 不把 Tauri updater 私钥写入仓库。

## Desktop Session Token

Tauri 桌面壳启动流程：

```text
generate 32-byte random token
  ↓
manage DesktopSession state
  ↓
spawn backend with:
  COGNINOTE_DESKTOP=true
  COGNINOTE_DESKTOP_SESSION_TOKEN=<token>
  ↓
health check /api/system/status with token header
  ↓
open WebView at http://127.0.0.1:{port}/
```

后端新增 `DesktopSessionTokenFilter`：

- 只在 `app.desktop.enabled=true` 时生效。
- 只匹配 `/api/**`。
- 要求 header `X-CogniNote-Desktop-Session` 与启动期 token 一致。
- 使用 `MessageDigest.isEqual` 比较 token 字节，避免不必要的早停比较泄漏。
- 拒绝请求时返回 `401`：

```json
{
  "success": false,
  "code": "UNAUTHORIZED",
  "message": "Desktop session token is missing or invalid",
  "data": null,
  "timestamp": 1780000000000
}
```

该保护不是用户身份系统，也不是进程级沙箱；它解决的是同机其他网页、脚本或旁路进程直接扫本机端口调用业务 API 的问题。若前端出现 XSS，token 仍可能被同源脚本读取，因此模型配置、文件路径和 API Key 仍需要继续按本地应用安全边界处理。

## Auto Update

Tauri updater 由 Rust commands 封装：

```text
get_desktop_session_token() -> string
check_desktop_update(channel: "stable" | "preview") -> UpdateInfo | null
install_desktop_update(channel: "stable" | "preview") -> InstallResult
```

运行时 endpoint：

```text
stable  -> https://github.com/ItQianChen/cogninote-agent-design/releases/download/desktop-updater-stable/latest.json
preview -> https://github.com/ItQianChen/cogninote-agent-design/releases/download/desktop-updater-preview/latest.json
```

Tauri updater public key 通过编译期环境变量注入：

```text
COGNINOTE_TAURI_UPDATER_PUBLIC_KEY
```

如果 public key 未配置，更新命令会返回“自动更新未配置”，不会影响普通桌面使用。下载和安装进度通过 Tauri event `desktop-update-progress` 推送给前端，事件类型为 `Started`、`Progress`、`Finished`、`Error`。

## Frontend UX

设置中心新增“系统 / 应用更新”：

- 显示当前通道、当前版本、可用版本、发布日期和 release notes。
- 通道偏好保存在 `localStorage`，默认 `stable`。
- `preview` 是显式 opt-in，用于测试/预发布资产。
- 手动检查更新时展示状态；启动自动检查默认静默，只有发现新版本才弹窗。
- 用户确认后调用安装命令，下载完成后安装并重启应用。

## GitHub Release Manifests

固定 manifest release/tag：

```text
desktop-updater-stable
desktop-updater-preview
```

manifest 资产固定为 `latest.json`，格式遵循 Tauri v2 静态 JSON：

```json
{
  "version": "0.1.34",
  "notes": "CogniNote 0.1.34 signed",
  "pub_date": "2026-06-13T00:00:00.000Z",
  "platforms": {
    "windows-x86_64": {
      "signature": "<.sig file content>",
      "url": "https://github.com/ItQianChen/cogninote-agent-design/releases/download/v0.1.34/CogniNote-0.1.34-windows-x64-signed-installer.exe"
    },
    "darwin-aarch64": {
      "signature": "<.sig file content>",
      "url": "https://github.com/ItQianChen/cogninote-agent-design/releases/download/v0.1.34/CogniNote-0.1.34-macos-arm64-signed.app.tar.gz"
    }
  }
}
```

CI 使用 `scripts/build-updater-manifest.mjs` 合并平台条目。同一版本下，Windows 和 macOS workflow 可以分开运行，后运行的平台会保留先前平台条目；版本变化时重新开始新的 `platforms` 集合。

## Signing Policy

Tauri updater 签名用于校验更新包完整性，不能替代 OS 代码签名。

CI secrets：

```text
TAURI_UPDATER_PUBLIC_KEY
TAURI_SIGNING_PRIVATE_KEY
TAURI_SIGNING_PRIVATE_KEY_PASSWORD
```

`TAURI_SIGNING_PRIVATE_KEY_PASSWORD` 可选。`TAURI_SIGNING_PRIVATE_KEY` 必须只放在 GitHub Secrets 或发布机器环境变量里，不能进入仓库。public key 可以作为 Secret 注入构建，最终编译进桌面壳。

生成密钥：

```powershell
npm --prefix cogniNote-agent-front run tauri -- signer generate -w ~/.tauri/cogninote-updater.key --ci
```

更新包签名必须在最终产物稳定后生成或覆盖。Windows 如果先对 installer 做 Authenticode，再生成 updater `.sig`；macOS 如果先 notarize/staple，再生成 `.app.tar.gz` 并签 updater `.sig`。否则后处理会改变文件内容，导致 Tauri updater 校验失败。

## Test Plan

- 后端：MockMvc 覆盖桌面模式下无 token、错误 token、正确 token，以及非桌面模式无 token 兼容。
- 前端：`npm --prefix cogniNote-agent-front run build`，验证普通 JSON API 和聊天 SSE 能注入 token。
- Tauri：`cargo check --manifest-path cogniNote-agent-front/src-tauri/Cargo.toml`，验证 updater commands 和插件初始化。
- Manifest：运行 `node scripts/build-updater-manifest.test.mjs`，验证 `latest.json` 合并 Windows/macOS 平台条目并读取最终 `.sig` 内容。
- 手工：从旧版安装到新版，确认设置页可检查 stable/preview；发现更新后确认安装，安装后重启进入新版本；无 token 的本机 curl 请求在桌面模式下返回 `401`。

## Assumptions

- `v0.1.32-test.1` 和 `v0.1.33-test.1` 没有 updater manifest 和 `.sig`，不能 retroactively 自动更新；自动更新从第 27 阶段后的包开始生效。
- 匿名访问 GitHub Release 静态资产不依赖 GitHub REST API 限流。
- 没有 Tauri updater public key 的本地构建仍可正常启动，只是设置页检查更新会提示未配置。

## References

- [Tauri v2 Updater 官方文档](https://v2.tauri.app/plugin/updater/)：静态 JSON、runtime endpoints/pubkey、`.sig` 内容和 signer 命令约束。
