# 第 24 阶段计划：CogniNote 工作台 UI/UX 全面改造

## Summary

第 24 阶段是一次前端 UI/UX 重构：把当前“左侧会话 + 聊天页 + 设置中心”的分散界面升级为统一的本地知识工作台。设计方向采用 Minimalism & Swiss Style：低装饰、强层级、信息密集但可扫读、系统字体、语义 token、完整浅色/深色/系统主题支持。

本阶段只调整前端信息架构、视觉系统和交互组织，不改变后端 API、数据库结构或业务数据模型。

## 设计依据

- `ui-ux-pro-max`：生产力工具优先清晰、可访问、低视觉噪音、44px 交互目标、token 化主题和 `prefers-reduced-motion`。
- AnySearch 外部参考：Open Notebook 的三栏知识工作台，以及 OpenAI UI guidelines 中轻量卡片、系统色、克制动作和不打断对话主流程的原则。

## Key Changes

- 信息架构重做为三层工作台：全局左 rail、上下文侧栏、主工作区。
- `/chat` 保持默认入口，重做为“对话主区 + 可折叠来源/证据 Inspector + 底部 composer”。
- `/knowledge` 升级为一等知识库工作区，承接目录导入、文档状态、索引状态和检索测试。
- `/settings` 改为纯配置中心：外观、系统信息、模型配置、聊天/检索策略。
- `/model-config` 兼容跳转到 `/settings?item=model-chat`，避免旧入口 404。
- 主题系统升级为 `system / light / dark` 三选项，旧 localStorage `dark/light` 值继续兼容。
- 视觉系统收敛到语义 token：surface、text、border、focus、spacing、radius、shadow、motion。
- 新增或重构组件边界：`workspace-shell`、`workspace-rail`、`context-sidebar`、`source-inspector`、`knowledge-workbench-view`、`system-status-card`。

## Public Interfaces

- 后端 API 不新增、不改字段。
- 前端路由保持兼容：`/chat`、`/knowledge`、`/settings` 保留；`/model-config` 重定向到设置页模型配置项。
- `theme` store 支持 `system / light / dark`，并监听 `prefers-color-scheme`。
- `layout` store 维护上下文侧栏和来源 Inspector 状态。
- 设置页当前项通过 `?item=system-info|model-chat|model-embedding|chat-retrieval|appearance` 表达，刷新后保持位置。

## Test Plan

- `npm --prefix cogniNote-agent-front run build`
- `mvn test`
- 浏览器截图验证：1280×820、1440×900、1024×768，浅色/深色/系统主题。
- 场景覆盖：空会话、长会话、流式生成、停止生成、错误消息、带来源回答、50+ 会话、无模型 API Key、无向量模型 API Key。
- 知识库页覆盖：无目录、导入后目录、索引状态刷新、检索无结果、检索有结果。
- 设置页覆盖：系统信息、模型配置、主题切换、追问补全策略；刷新后 query item 保持。
- 可访问性检查：键盘 Tab 顺序、Escape 关闭 Inspector/弹层、focus ring 可见、reduced-motion 生效、按钮/图标均有 aria-label 或文本。
- 兼容性检查：旧 localStorage `cogninote-theme=dark/light` 正常迁移；`/model-config` 旧入口不 404。

## Assumptions

- 本阶段只做前端 UI/UX 和文档阶段顺延。
- 默认设计方向为克制的专业桌面工具，不做营销页、重渐变、大卡片堆叠或装饰性动效。
- 知识图谱顺延为第 25 阶段能力。
- 如果实现过程中发现某个页面需要后端新字段，先用现有数据完成 UI，不把 API 扩展混入本阶段。
