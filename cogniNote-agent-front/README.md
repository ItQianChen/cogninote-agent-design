# cogniNote-agent-front

CogniNote Agent 的 Vue 3 前端。当前前端使用 Vue Router + Pinia + API client 分层，包含对话、知识库、模型配置和设置四个页面；生产构建由 Spring Boot 托管，桌面版由 Tauri WebView 加载后端页面。

## Recommended IDE Setup

[VS Code](https://code.visualstudio.com/) + [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) (and disable Vetur).

## Recommended Browser Setup

- Chromium-based browsers (Chrome, Edge, Brave, etc.):
  - [Vue.js devtools](https://chromewebstore.google.com/detail/vuejs-devtools/nhdogjmejiglipccpnnnanhbledajbpd)
  - [Turn on Custom Object Formatter in Chrome DevTools](http://bit.ly/object-formatters)
- Firefox:
  - [Vue.js devtools](https://addons.mozilla.org/en-US/firefox/addon/vue-js-devtools/)
  - [Turn on Custom Object Formatter in Firefox DevTools](https://fxdx.dev/firefox-devtools-custom-object-formatters/)

## Customize configuration

See [Vite Configuration Reference](https://vite.dev/config/).

## Project Setup

```sh
npm ci
```

### Compile and Hot-Reload for Development

```sh
npm run dev
```

Vite 会把 `/api` 代理到本地 Spring Boot 后端：

```text
http://127.0.0.1:18080
```

### Compile and Minify for Production

```sh
npm run build
```

生产构建产物会输出到 `dist/`，整包构建时由根目录 Maven `with-frontend` profile 复制进 Spring Boot 静态资源目录。

### Desktop Build

桌面打包入口在项目根目录，不建议从前端子目录直接运行完整构建：

```powershell
cd D:\code\JavaCode\cogninote-agent-design
.\scripts\build-desktop-app.ps1 -SkipTests
```

如需只执行 Tauri 构建，必须先确保根目录 `target/desktop/backend/CogniNoteBackend/` 已由 `scripts/build-desktop-backend.ps1` 生成：

```sh
npm run desktop:build
```

桌面构建、`.ps1` 脚本运行方式、产物路径和常见问题见根目录文档 `docs/desktop-build-guide.md`。
