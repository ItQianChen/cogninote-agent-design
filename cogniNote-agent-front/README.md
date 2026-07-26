# 知记空间（CogniNote）Frontend

知记空间（CogniNote）的 Vue 3 前端。当前前端是桌面对话应用形态：`/chat` 显示左侧临时会话列表和主对话流，`/settings` 使用独立全屏设置中心，左侧归拢系统、知识库和模型配置，右侧展示具体设置内容。

前端生产构建由 Spring Boot 托管，桌面版由 Tauri WebView 加载后端页面。

## 工程结构

```text
src/
  ├─ api/          # 统一 JSON API client、知识库健康/维护、联网搜索设置 API 和 SSE stream parser
  ├─ components/   # 应用壳、来源列表、Markdown 渲染、知识库总览、目录管理面板/健康抽屉等复用组件
  ├─ router/       # /chat、/settings、/knowledge、/model-config
  ├─ stores/       # Pinia 状态：system、documents、search、knowledgeHealth、knowledgeMaintenance、modelConfig、webSearchSettings、chat、theme
  ├─ styles/       # 基础、控件、知识库、桌面对话、Markdown、主题、响应式样式
  └─ views/        # 页面级组件
```

维护约束：

- Assistant 消息通过 `markdown-renderer.vue` 渲染 Markdown，禁用原始 HTML。
- RAG 和网页引用来源由 `source-list.vue` 自己维护折叠状态，避免消息组件膨胀。`sourceType=WEB` 的来源来自 SSE `tool` 事件，展示标题、URL 和 provider，点击时打开外部网页；缺少 `sourceType` 的旧来源按 `LOCAL` 处理。
- 用户引用助手回复片段由 `chat` store 的 `pendingReferences` 管理；发送区只显示独立引用标签，不把标签嵌入 textarea。发送后 user 消息的 `references` 字段负责刷新恢复和悬停预览。
- 知识库总览页只放全库健康摘要、最近目录和轻量目录管理入口；导入目录使用 `knowledge-folder-import-dialog.vue` 弹窗，目录同步、启停、删除、重建和文档展开集中在 `knowledge-directory-manager-panel.vue`。
- 目录管理面板使用前端本地搜索、筛选和分页处理当前目录快照；分页控件走全局 Element Plus 中文 locale，避免英文 `items/page` 混入中文界面。
- 知识库健康快照由 `knowledge-health` store 管理；首屏只加载全库轻量快照，目录级失败文件、缺失文件、新增文件和维护记录只在打开问题抽屉或维护记录弹窗时读取。停用目录是用户主动排除检索范围的状态，不计入问题数量，也不触发“查看问题”。
- 知识库维护任务由 `knowledge-maintenance` store 作为唯一状态源；目录同步、补写索引、重建、启停、删除和全库重建都通过 `/api/knowledge-maintenance/runs/**` 入队，前端不能再用局部 `busyFolderIds` 或 `isRebuildingIndex` 推断真实任务状态。
- 等待中的维护任务可以取消，运行中的任务只展示运行中状态、阶段、当前目录或路径和长任务提示；不要渲染虚假百分比进度条。
- 维护任务终态后必须通过 `knowledge-maintenance.refreshKnowledgeSnapshots()` 同时刷新维护队列、目录列表、索引状态、健康快照和已打开的目录详情，避免资料总览页、目录管理和可信状态页面数据分叉。
- 导入、同步、补写索引、重建、停用和删除等高影响维护动作使用结构化 Element Plus 二次确认；导入、补写和重建类任务完成后进入用户确认式结果弹窗，维护记录通过分页弹窗加载。
- 模型设置页的 Embedding “请求限速”区域必须和后端 `embeddingRequestsPerMinute`、`embeddingTokensPerMinute`、`embeddingBatchSize` 字段保持一致；预设档位只是表单快捷入口，保存时仍以表单中的 RPM、TPM 和 Batch 数值为准。
- 联网搜索全局配置由 `web-search-settings` store 和 `/api/web-search/settings` 维护，不要塞进 `chat` 或模型配置 store。后端响应永远不会返回 API Key 明文；前端只能把 Key 作为输入框编辑草稿短暂保存，保存成功或重新拉取远端设置后清空。
- “启用联网搜索”必须和 Key 状态联动：没有已保存 Key 且当前输入框也没有待保存 Key 时，设置页启用开关保持禁用；聊天设置弹层也不能打开本轮联网，只显示到 `/settings?item=web-search` 的入口。
- 聊天设置弹层需要保持紧凑工具布局：知识库和联网两个 switch 在第一行，检索模式与 Top K 可换到下一行；窄宽度下控件必须换行，不能撑出弹层。
- 收到 SSE `tool` 事件时，`chat` store 要把网页 sources 合并进当前 assistant message；不要等 `done` 才展示，也不要把网页来源写成本地 chunk 追问入口。
- 主题偏好由 `theme` store 写入 `localStorage`，通过 `html.theme-dark` / `html.theme-light` 控制样式。
- Element Plus 用于设置中心和知识库维护页的标准控件、提示、确认操作和分页；聊天主界面继续保持自定义 UI。
- 设置中心不显示左侧对话栏，避免非聊天能力继续占用聊天布局。

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

### Test and Browser Smoke

```powershell
$ErrorActionPreference = 'Stop'
npm run test
npm run test:coverage
npm run build
node ..\scripts\check-frontend-bundle-budget.mjs
npm run test:e2e
```

Vitest 使用独立配置，不加载 Vite DevTools；Playwright smoke 使用隔离临时存储和随机端口。模型连接与 RAG SSE 使用确定性 route mock，目录导入、搜索、维护和重启持久化走真实本地后端。完整门禁说明见 `../docs/testing-and-release-gates.md`。

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
