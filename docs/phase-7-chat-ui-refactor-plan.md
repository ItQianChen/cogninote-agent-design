# CogniNote Agent 第七阶段任务计划：对话式前端重构

## Summary

第七阶段只做前端产品形态重构，不改后端接口和数据库。目标是把当前“功能页导航”改成桌面对话应用：左侧为会话列表，底部固定设置入口，主区域为对话流，输入区支持“使用知识库”开关。非聊天相关能力统一移动到设置页内。

原计划第八阶段的 SQLite 聊天记忆已顺延为第九阶段。第七阶段会先把前端状态模型和 UI 预留成可对接 SQLite 会话的结构，避免后续推倒重来。

## Key Changes

- 前端布局改为桌面聊天形态：左侧 sidebar 显示应用标识、新建对话、临时会话列表和设置入口；主区域显示消息流、引用来源、输入框、发送/停止按钮。
- 设置页承载知识库、模型配置、索引状态、系统状态等非聊天功能。
- 不新增后端 API，继续使用现有 `POST /api/chat/stream`。
- “使用知识库”第一版只做全库开关。开启时沿用当前 RAG；关闭时显示纯对话将在第九阶段接入，不发送假请求。
- 前端会话只做运行期临时状态，刷新页面后不保证恢复。真正跨重启记忆由第九阶段 SQLite 实现。

## Implementation Changes

- `AppShell` 改为左侧栏 + 主内容区布局，移除顶部大标题式工作台。
- `/chat` 保持默认页面；`/settings` 成为非聊天能力总入口，并使用独立全屏布局，不再显示左侧对话栏；`/knowledge` 和 `/model-config` 保留路由但从主导航隐藏。
- `chat` store 增加 `sessions/messages/activeSessionId`，发送时先追加 user 消息，再把 SSE delta 追加到 assistant 消息。
- 聊天页改为消息气泡流：支持 user、assistant、error、loading、stopped 状态；assistant 消息下方展示本轮引用来源。
- Assistant 消息使用 Markdown 渲染，禁用原始 HTML，避免外部模型输出直接注入页面。
- 引用来源默认折叠，用户点击后展开文件名、路径、标题/页码、chunk 和预览。
- 对话设置从输入框上方的大块区域改为输入框右侧按钮触发的浮层，包含知识库开关、关键词/向量/混合模式和 Top K。
- 发送/停止使用右侧圆形图标按钮；未输入时置灰，输入后点亮，生成中显示旋转按钮并用于停止对话。
- SSE `conversationId` 保留为协议字段，但不在用户界面展示。
- 设置页内部用分段入口归拢系统状态、知识库管理、模型配置三个区域，复用现有知识库和模型配置逻辑。
- 设置页增加主题选择，支持深色/夜间和日间主题，偏好保存到本机 `localStorage`。
- 样式采用桌面应用风格，左侧 sidebar 固定宽度，移动端降级为上方会话切换与单列聊天流。

## Implementation Notes

本阶段已追加工程化样式拆分和日志可观测性补充：

- `src/styles/` 拆分为基础、控件、桌面对话、结果列表、Markdown、主题和响应式样式模块，避免继续膨胀单个 CSS 文件。
- `markdown-renderer.vue` 只负责把模型输出渲染为受控 Markdown，不承担消息状态和来源展示。
- `source-list.vue` 内部维护折叠状态，消息组件只负责传入 sources。
- `theme` store 只管理主题偏好和 `html` 根节点 class，具体颜色变量由 `theme.css` 承载。
- Spring Boot 业务日志写入 `%APPDATA%/CogniNote/logs/app.log`，Tauri 后端启动输出写入 `%APPDATA%/CogniNote/logs/desktop-backend.log`，避免桌面版出错时无日志可查。

## Phase 8/9 Follow-up

第八阶段先做多模型配置中心：对话模型和 Embedding 模型独立维护、独立激活。SQLite 聊天记忆顺延到第九阶段。

第九阶段聊天记忆计划：

- 新增 `chat_sessions` 与 `chat_messages` SQLite 表。
- 新增会话 CRUD 与消息查询 API。
- 扩展 `POST /api/chat/stream` 请求体，支持 `conversationId` 与 `useKnowledgeBase`。
- `useKnowledgeBase=false` 时走纯模型对话；`useKnowledgeBase=true` 时走当前 RAG，并注入最近 N 轮会话历史。
- 第一版记忆只做最近 N 轮短期会话记忆，不做长期摘要、用户画像、向量化历史记忆。

## Test Plan

- `npm --prefix cogniNote-agent-front run build` 通过。
- `/chat` 默认打开为左侧会话 + 主对话布局。
- 新建对话、切换临时会话、发送问题、停止生成可用。
- RAG 开启时沿用现有 `/api/chat/stream`，回答和 sources 正常显示。
- 关闭“使用知识库”时不发送请求，并显示后续阶段提示。
- 设置页能进入模型配置、知识库管理、索引状态和系统状态。
- 设置页不显示左侧对话栏，点击“返回对话”回到 `/chat`。
- Markdown 回答能渲染标题、列表、代码块和链接，原始 HTML 不执行。
- 引用来源可折叠/展开，默认不挤占对话流。
- 对话设置浮层打开/关闭正常，发送/停止按钮状态和 tooltip 正常。
- 深色/夜间与日间主题切换后刷新页面仍保持选择。
- 刷新 `/chat`、`/settings` 不白屏。

## Assumptions

- 第七阶段严格前端优先，不新增数据库和后端 API。
- SQLite 聊天记忆放到第九阶段实现。
- 知识库选择第一版只做全库开关，不做文档级筛选。
- 第七阶段可以调整前端路由导航，但不删除旧路由，避免刷新和历史链接失效。
- 不新增大型 UI 组件库。
