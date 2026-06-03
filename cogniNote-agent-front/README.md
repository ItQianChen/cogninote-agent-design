# CogniNote Frontend

CogniNote Agent 的 Vue 3 前端。当前前端是桌面对话应用形态：`/chat` 显示左侧临时会话列表和主对话流，`/settings` 使用独立全屏设置页，归拢系统状态、主题、知识库和模型配置。

前端生产构建由 Spring Boot 托管，桌面版由 Tauri WebView 加载后端页面。

## 工程结构

```text
src/
  ├─ api/          # 统一 JSON API client 和 SSE chat stream parser
  ├─ components/   # 应用壳、来源列表、Markdown 渲染、分段控件等复用组件
  ├─ router/       # /chat、/settings、/knowledge、/model-config
  ├─ stores/       # Pinia 状态：system、documents、search、modelConfig、chat、theme
  ├─ styles/       # 基础、控件、桌面对话、Markdown、主题、响应式样式
  └─ views/        # 页面级组件
```

维护约束：

- Assistant 消息通过 `markdown-renderer.vue` 渲染 Markdown，禁用原始 HTML。
- 引用来源由 `source-list.vue` 自己维护折叠状态，避免消息组件膨胀。
- 主题偏好由 `theme` store 写入 `localStorage`，通过 `html.theme-dark` / `html.theme-light` 控制样式。
- 设置页不显示左侧对话栏，避免非聊天能力继续占用聊天布局。

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
