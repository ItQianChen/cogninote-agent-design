# CogniNote Agent 第九阶段任务计划：UI 视觉可读性与主题系统修正

## Summary

第九阶段聚焦当前桌面页面的视觉可读性问题，不新增业务能力。根据运行截图，当前 UI 存在三类主要问题：

- 字体观感发虚、局部文字过重或过大，信息密度和层级不稳定。
- 深色/夜间模式下黑白反差过大，白色卡片和深色背景并置时刺眼。
- 浅色和深色主题没有共享稳定设计 token，导致按钮、卡片、输入框、状态标签在不同页面里呈现割裂。

本阶段目标是建立一套统一、克制、适合桌面知识库工具的视觉系统：文字清晰、对比舒适、界面层级稳定，聊天页、设置页、知识库页和模型配置页保持一致。

原第九阶段“SQLite 聊天记忆”已顺延：第十阶段改做知识库目录管理与局部索引重建，第十一阶段改做智能体模型运行时重构，SQLite 聊天记忆顺延为第十二阶段。

计划文件落地路径：`docs/phase-9-ui-visual-readability-plan.md`。

## Key Changes

- 重建前端主题 token：
  - 明确定义 `color-bg`、`color-surface`、`color-surface-muted`、`color-border`、`color-text`、`color-text-muted`、`color-accent`、`color-danger`。
  - 深色主题不再使用纯黑大背景 + 纯白卡片的强烈撞色。
  - 日间主题降低大面积青绿色覆盖，避免浅色页发灰、发虚。
- 调整字体系统：
  - 使用系统 UI 字体栈，优先保证 Windows WebView/Chrome 下中文清晰度。
  - 正文字号以 `15px/16px` 为基准，行高控制在 `1.45-1.6`。
  - 禁止大面积过粗字体；标题、卡片值、按钮文本分级使用 `500/600/700`，避免全部加粗。
  - 路径、URL、模型 ID 等长文本使用更可读的 monospace 尺寸和换行策略。
- 收敛深色主题反差：
  - 页面背景使用深灰蓝黑，不用纯黑。
  - 卡片使用深色 surface，不用大块纯白。
  - 输入框在深色主题下使用浅深色表面或柔和浅色，但必须保证边框、文字和 placeholder 对比可读。
- 重构设置页视觉层级：
  - 顶部 header 降低高度和阴影重量。
  - 设置 tab 使用更轻的 segmented control，不做高亮色大块覆盖。
  - 系统统计卡、模型配置表单、知识库列表统一卡片样式。
- 重构模型配置页：
  - 左侧配置列表与右侧编辑器统一 spacing 和边框。
  - Active 配置高亮改为低饱和边框 + 小徽标，不再整卡强青色覆盖。
  - 表单 label、输入框、按钮对齐统一，减少视觉跳动。
  - 底部 summary card 避免文字巨大、截断难读，长 URL/模型 ID 支持换行。
- 重构聊天页：
  - 左侧 sidebar 降低按钮和卡片的饱和度。
  - 首页空状态卡片缩小阴影，避免像漂浮广告卡。
  - 顶部状态 pill 降低亮度，未配置状态使用温和警示色而不是高亮色。
  - 输入区 placeholder、禁用发送按钮和提示文字提高可读性。
  - 对话设置浮层使用受控 switch、分段检索模式和紧凑 Top K 输入，不再使用原生 checkbox 作为视觉状态源。

## Design Principles

### 1. 工具型产品优先

CogniNote 是知识库与对话工具，不是营销页。界面应该安静、稳定、可扫描：

- 少用大面积渐变和强阴影。
- 少用极高饱和颜色。
- 重点区域靠 spacing、边框、字号和小面积 accent 区分。
- 页面内容应该像桌面软件，而不是落地页。

### 2. 对比要足够，但不能刺眼

验收目标：

- 正文文本对背景对比度不低于 WCAG AA 的 4.5:1。
- 辅助文本对比度不低于 3:1，并且不能承担关键操作信息。
- 深色主题中，卡片和页面背景的亮度差控制在舒适区间，避免“黑底白板”。

### 3. 字体清晰优先

字体策略：

```css
font-family:
  Inter,
  ui-sans-serif,
  system-ui,
  -apple-system,
  BlinkMacSystemFont,
  "Segoe UI",
  "Microsoft YaHei UI",
  "Microsoft YaHei",
  sans-serif;
```

维护注意：

- 不使用负 letter-spacing。
- 不使用 viewport width 缩放字体。
- 长文本容器必须设置 `overflow-wrap: anywhere` 或合理的换行策略。
- URL、路径和模型 ID 的 monospace 不应超过正文太多，避免截图中 summary card 文字压迫。

## Implementation Changes

### 样式架构

- 保持 `src/styles/` 工程化拆分，不回到超长 `base.css`。
- 新增或重构：
  - `src/styles/tokens.css`：主题变量、字体、阴影、圆角、间距。
  - `src/styles/theme.css`：只做 `theme-dark` / `theme-light` 变量覆盖。
  - `src/styles/settings.css`：设置页布局和系统/知识库/模型统一外壳。
  - `src/styles/model-config.css`：仅保留模型配置业务局部样式。
- 样式导入顺序固定为：foundation → tokens/theme → controls → layout/page modules → responsive。
- 对关键 token 添加中文维护注释，说明为什么不使用纯黑/纯白和高饱和 accent。

### 主题 Token 建议

深色/夜间：

```css
--color-bg: #0f171d;
--color-bg-elevated: #141f27;
--color-surface: #18242d;
--color-surface-muted: #20303a;
--color-border: rgba(148, 163, 184, 0.18);
--color-text: #e7eef5;
--color-text-muted: #9fb0c1;
--color-accent: #2f9e93;
--color-accent-soft: rgba(47, 158, 147, 0.16);
--shadow-panel: 0 16px 42px rgba(0, 0, 0, 0.24);
```

日间：

```css
--color-bg: #f5f8fa;
--color-bg-elevated: #ffffff;
--color-surface: #ffffff;
--color-surface-muted: #eef5f6;
--color-border: #d8e3e8;
--color-text: #102033;
--color-text-muted: #617386;
--color-accent: #187c73;
--color-accent-soft: #dff3ef;
--shadow-panel: 0 14px 36px rgba(15, 23, 42, 0.08);
```

以上值是初始方案，落地时可按浏览器截图微调，但不能回到纯黑、纯白、强青色铺底。

### 组件级调整

- `AppShell`
  - Sidebar 背景和主区域背景使用 token。
  - 新建对话按钮降低饱和度，hover 只小幅变化。
- `ChatView`
  - 空状态卡片减少宽度和阴影。
  - 顶部状态 pill 使用低饱和背景，未配置状态使用 warning token。
  - 输入区按钮保持 44px 最小点击尺寸。
  - 对话设置浮层拆到 `chat-settings-popover.vue`，由 `props -> emit -> chat store setter` 驱动，避免 Pinia 状态、浮层摘要和原生表单控件显示不一致。
  - “使用知识库”改为受控 switch，Top K 输入固定宽度并在窄屏降级为单列，避免设置面板撑开或遮挡输入框。
- `SettingsView`
  - Header 高度收敛，title 不超过页面视觉重心。
  - Tab/section 之间使用统一间距。
  - 系统统计卡在深色主题下不使用白底。
- `ModelConfigView`
  - 配置列表 active 状态改为 border + badge。
  - 编辑器表单背景与页面背景分层，不用强渐变。
  - Summary card 的字段值减小字号，长文本换行。
- `KnowledgeView`
  - 与设置页视觉系统统一，不再单独漂浮出一套卡片风格。

## Documentation Changes

- 新增 `docs/phase-9-ui-visual-readability-plan.md`。
- 更新 `docs/cogninote-agent-design.md`：
  - Milestone 9 改为 UI 视觉可读性与主题系统修正。
  - SQLite 聊天记忆顺延到 Milestone 12。
  - 前端页面设计补充视觉系统约束。
- 更新 `docs/phase-7-chat-ui-refactor-plan.md`、`docs/phase-8-multi-model-configuration-plan.md`、`docs/phase-10-knowledge-base-folders-plan.md` 和 `docs/phase-11-agent-model-runtime-refactor-plan.md` 中关于聊天记忆阶段的描述。
- README 只增加 Phase 9 计划入口，不展开完整设计细节。

## Test Plan

- 构建验证：
  - `npm --prefix cogniNote-agent-front run build`
- 页面手测：
  - 日间主题：`/chat`、`/settings?section=system`、`/settings?section=knowledge`、`/settings?section=model` 均无文字发虚和低对比问题。
  - 深色/夜间主题：卡片、输入框、按钮、状态 pill 不再出现黑白强撞色。
  - 模型配置页长 URL、模型 ID、时间字段不会撑破卡片或大字号挤压。
  - 设置页顶部 header 高度适中，tab 不抢主内容视觉重心。
  - 聊天输入区、发送/停止按钮、设置浮层状态清晰。
  - 对话设置浮层关闭再打开后，知识库 switch、检索模式、Top K 与顶部摘要保持一致。
- 浏览器截图验收：
  - 至少验证 1440px、1920px 宽度。
  - 如果条件允许，用 Playwright 或浏览器截图对比日间/夜间两套主题。
- 可访问性验收：
  - 正文和关键按钮文本对比度达到 WCAG AA。
  - 焦点态可见。
  - 禁用态按钮不依赖颜色单独表达状态。

## Assumptions

- 第九阶段只做 UI 视觉质量、主题系统和可读性修正，不新增后端 API。
- 不引入大型 UI 组件库。
- 继续保留当前 Vue Router、Pinia、现有组件拆分结构。
- 图标继续优先使用 `lucide-vue-next`。
- SQLite 聊天记忆、纯模型对话和会话持久化顺延到第十二阶段。
