# CogniNote Agent 第十二阶段任务计划：AI 流式 Markdown 渲染重构

## Summary

第十二阶段改为修复 AI 输出 Markdown 渲染不完整的问题，原第十二阶段“SQLite 聊天记忆”顺延为第十三阶段。

本阶段从“只换 Markdown 渲染器”扩展为“AI 流式 Markdown 输出链路修正”：前端用 `markstream-vue` 替换当前基础 `markdown-it` 封装，同时修复 SSE 解析和后端 Spring AI 流式 chunk 处理，保证标题、列表、表格、任务列表、代码块和流式输出中的未完成 Markdown 不因为空格/换行被吞而渲染失败。

实施状态：已落地。聊天页 assistant 消息改用异步加载的 `ai-markdown-renderer.vue`，旧 `markdown-renderer.vue` 保留一个阶段作为兼容组件。后端已保留 Spring AI 返回的空白 chunk，前端 SSE parser 只移除 `data:` 后的协议分隔空格，不再对内容做 `trimStart()`。第十三阶段后，普通 SSE 断开会继续生成并保存完整 assistant 消息，只有用户显式停止才落库为 `STOPPED`。

## Key Changes

- 新增依赖 `markstream-vue`，使用其 `MarkdownRender` 作为聊天消息 Markdown 渲染器。
- 新增 `ai-markdown-renderer.vue`，接收 `content` 和 `final`，使用 `htmlPolicy="escape"`，外部模型输出的原始 HTML 只按文本显示。
- 渲染器使用流式友好配置：`max-live-nodes=0`、`batch-rendering=true`、`typewriter=true`、`fade=false`、`smooth-streaming=true`。
- `chat-view.vue` 将 assistant 消息从旧 `markdown-renderer.vue` 切到新组件；user/error/stopped 状态保持原逻辑。
- 新增 `ai-markdown.css`，只覆盖聊天消息里的 markstream 容器，保证表格、代码块和长文本不会撑破气泡。
- `markstream-vue` 通过 `defineAsyncComponent` 按需加载，避免设置页和空对话首屏被重型 Markdown 渲染器拖慢。
- `SpringAiChatRuntime` 只过滤 `null` 和空字符串，不再过滤空格、换行等 `isBlank()` chunk；这些空白可能是 Markdown 标题、列表、缩进和代码块语法的一部分。
- `chat-stream.js` 的 SSE parser 保留 `data:` 内容里的前导空白，只移除协议允许的一个分隔空格，避免 `### 标题`、`- 列表项` 等格式在流式拼接后变成非法 Markdown。
- `ChatSseEventMapper` 避免在 SSE 发送失败时调用 `completeWithError()`，防止 Spring MVC 在 `text/event-stream` 响应上再写 JSON `ApiResponse`。
- `POST /api/chat/stream/{requestId}/cancel` 只表示用户显式停止；普通浏览器刷新、切页或连接断开不取消后端模型流，为第十三阶段完整保存 assistant 消息预留空间。
- `application.yaml` 中 RAG prompt 明确要求模型输出标准 Markdown：标题符号后带空格、列表符号后带空格、代码块使用 fenced code block、原始 HTML 禁止输出。

## Streaming Contract

- SSE 仍是 `meta -> delta -> done/error`，但 `meta` 中包含 `requestId`，前端停止生成时用它调用取消接口。
- `delta.text` 是模型原始文本增量，客户端和服务端都不能对其做 `trim()`、`trimStart()` 或 `isBlank()` 过滤。
- SSE 是观察通道，不是生成任务本身。只有用户点击停止才取消模型订阅；普通连接断开时后端仍消费到完成，第十三阶段聊天记忆会保存完整回答。
- 一旦响应 Content-Type 进入 `text/event-stream`，全局异常处理不能再写 `ApiResponse` JSON；错误应尽量映射为 SSE `error` 事件，兜底只关闭响应。

## Documentation Changes

- `docs/cogninote-agent-design.md` 中 Milestone 12 改为 AI 流式 Markdown 渲染重构，并补充 SSE 空白保留、显式取消和 event-stream 异常处理约束。
- 原 SQLite 聊天记忆已在 Milestone 13 落地。
- `README.md` 更新 Phase 12 文档入口、当前状态和流式 Markdown 链路说明。
- `docs/phase-7-chat-ui-refactor-plan.md`、`docs/phase-8-multi-model-configuration-plan.md`、`docs/phase-9-ui-visual-readability-plan.md`、`docs/phase-10-knowledge-base-folders-plan.md`、`docs/phase-11-agent-model-runtime-refactor-plan.md` 中关于聊天记忆阶段的引用同步顺延。

## Test Plan

- `npm --prefix cogniNote-agent-front run build`
- 手测 assistant 消息：标题、粗体、列表、任务列表、表格、引用、行内代码、fenced code block、未闭合代码块流式追加、长代码横向滚动。
- 检查日间/夜间主题下 Markdown 字体、代码块、表格、链接颜色可读。
- 检查流式生成时不闪烁、不整段跳动，消息气泡宽度不被表格或代码撑破。
- 后端流式链路改动需要覆盖：Spring AI 空白 chunk 不被丢弃、SSE 取消注册、`text/event-stream` 异常不再尝试写 JSON。
- 实现阶段已验证：
  - 第十二阶段当时验证过 `CogninoteChatAgentTests`；第十八阶段后对应测试已改为 `ChatAgentRouterTests`
  - `mvn "-Dtest=SpringAiChatRuntimeTests,ChatControllerTests,ChatAgentRouterTests" test`
  - `npm --prefix cogniNote-agent-front run build`
- 不做桌面整包验证。

## Assumptions

- 本阶段只解决 AI Markdown 渲染质量和流式传输正确性；聊天记忆和纯模型对话已由第十三阶段承接。
- SSE 协议保持向后兼容，新增的取消端点只服务“用户显式停止”。
- `markstream-vue` 的 Mermaid、KaTeX、Monaco、D2 等 peer dependencies 暂不安装；第一版只启用基础 Markdown 渲染。
- 原始 HTML 继续转义，不能为了渲染效果开放模型 HTML 注入。
- 如果 `markstream-vue` 与现有样式冲突，优先用局部样式覆盖，不全局重写主题系统。
