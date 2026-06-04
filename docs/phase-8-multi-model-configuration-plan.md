# CogniNote Agent 第八阶段任务计划：多模型配置

## Summary

第八阶段聚焦“多模型配置中心”。当前单条 `active model_config` 会被重构为多条模型配置，且对话模型和 Embedding 模型独立维护、独立激活、独立测试。原计划中的 SQLite 聊天记忆已继续顺延为第十二阶段；第九阶段先处理运行截图暴露出的 UI 视觉可读性与主题系统问题，第十阶段改做知识库目录管理与局部索引重建，第十一阶段改做智能体模型运行时重构。

本阶段计划文件落地路径：`docs/phase-8-multi-model-configuration-plan.md`。

## Key Changes

- 新增模型配置角色：`CHAT`、`EMBEDDING`。
- 用户可以分别新建、编辑、删除、测试、激活对话模型配置和 Embedding 模型配置。
- 对话模型配置字段：配置名称、Provider、Base URL、API Key、模型 ID、Temperature、默认 Top K。
- Embedding 模型配置字段：配置名称、Provider、Base URL、API Key、模型 ID、Embedding 维度。
- RAG 流程改为：LLM 调用读取 active `CHAT` 配置，向量化和索引读取 active `EMBEDDING` 配置。
- DashScope 使用默认百炼地址；OpenAI-compatible 使用用户自定义 Base URL，并调用 `/models`、`/chat/completions`、`/embeddings`。
- API Key 继续支持前端显示、隐藏、复制；仍明文保存 SQLite，后续桌面安全阶段再做加密。

## Backend Changes

- 数据库新增 `model_configs` 表，替代单行 active 模型配置：
  - `id`
  - `role`
  - `provider`
  - `display_name`
  - `base_url`
  - `api_key`
  - `model_name`
  - `embedding_dimensions`
  - `temperature`
  - `default_top_k`
  - `is_active`
  - `created_at`
  - `updated_at`
- 启动迁移逻辑：
  - 如果旧 `model_config` 表存在 active 记录，则拆成一条 active `CHAT` 配置和一条 active `EMBEDDING` 配置。
  - 如果没有旧配置，则创建默认 DashScope 草稿配置，API Key 为空。
  - 每个 role 最多只能有一个 active 配置。
- 新 API：
  - `GET /api/model-configs?role=CHAT|EMBEDDING`
  - `GET /api/model-configs/active`
  - `POST /api/model-configs`
  - `PUT /api/model-configs/{id}`
  - `DELETE /api/model-configs/{id}`
  - `POST /api/model-configs/{id}/activate`
  - `POST /api/model-configs/test`
  - `POST /api/model-configs/models`
- 设置页专用快照 API：
  - `GET /api/model-configs/settings?role=CHAT|EMBEDDING`
  - `POST /api/model-configs/settings/configs`
  - `PUT /api/model-configs/settings/configs/{id}`
  - `DELETE /api/model-configs/settings/configs/{id}`
  - `POST /api/model-configs/settings/configs/{id}/activate`
- 旧 `/api/model-config` 暂时保留为 deprecated 兼容接口，前端本阶段切到新 API。
- 删除规则：settings 删除接口允许删除唯一配置，但会自动补一条默认 active 草稿，保证设置页始终能拿到 `selectedConfig`；旧 CRUD 删除后也会确保该 role 仍有 active 配置。
- 对迁移逻辑、active 唯一性、API Key 复用、DashScope/OpenAI-compatible URL 规范、Embedding 降级路径添加中文维护注释。

## Frontend Changes

- 设置页“模型”改成 `对话模型` / `Embedding 模型` 双 Tab。
- 每个 role 显示配置列表：配置名、Provider、模型 ID、Base URL、active 标记、更新时间。
- 每个 role 支持新建、编辑、保存、测试、获取模型、激活、删除。
- 表单按 role 动态展示字段：
  - 对话模型不显示 Embedding 维度。
  - Embedding 模型不显示 Temperature 和默认 Top K。
  - Embedding 模型使用用户输入模型 ID，不强制下拉选择。
- Pinia `model-config` store 重构为设置页快照模型：
  - `activeSummary` 保存顶部 Active Chat / Active Embedding。
  - `roleState.CHAT` 和 `roleState.EMBEDDING` 分别保存 `configs/selectedConfig/form/loading/saving/deleting/activating/error`。
  - 右侧表单直接绑定单一 `form`，不在组件内再维护第二份编辑表单。
  - 设置页切到“模型”时加载 `CHAT` 快照，点击 “Embedding 模型” 时加载 `EMBEDDING` 快照。
  - 模型页不使用整块 loading 遮罩，避免页签切换闪烁；请求失败保留旧表单并显示错误。
- 聊天页展示 active Chat / active Embedding 摘要，不再认为只有一个模型配置。
- 保存或激活 Embedding 配置后刷新索引状态，并提示用户如维度或模型变化，建议重建索引。

## Documentation Changes

- 新增本计划文件：`docs/phase-8-multi-model-configuration-plan.md`。
- 原 SQLite 聊天记忆调整为 Phase 12，Phase 9 改为 UI 视觉可读性与主题系统修正，Phase 10 改为知识库目录管理与局部索引重建，Phase 11 改为智能体模型运行时重构。
- 更新 `docs/phase-7-chat-ui-refactor-plan.md` 中关于第八阶段的描述。
- 更新 `docs/cogninote-agent-design.md` 里程碑顺序、API、表结构和模型配置说明。
- 更新 `docs/model-configuration-guide.md` 和 `docs/api-reference.md`。
- README 只保留入口说明，不塞入完整 API 细节。

## Test Plan

- 后端：
  - `mvn test` 通过。
  - 覆盖旧 `model_config` 到新 `model_configs` 的迁移。
  - 覆盖每个 role 的新增、编辑、列表、激活、删除。
  - 验证每个 role 只能有一个 active 配置。
  - 验证不能删除唯一 active 配置。
  - 验证 RAG 使用 active `CHAT`，Embedding 使用 active `EMBEDDING`。
  - 验证 OpenAI-compatible `/models`、`/chat/completions`、`/embeddings` URL 拼接正确。
- 前端：
  - `npm --prefix cogniNote-agent-front run build` 通过。
  - 设置页可分别创建多个对话模型和多个 Embedding 模型。
  - 激活对话模型不会覆盖 Embedding 配置。
  - 激活 Embedding 模型不会覆盖对话配置。
  - API Key 显示、隐藏、复制可用。
  - 刷新设置页后 active 配置和列表正常回显。
- 本阶段不做桌面整包打包验收；桌面打包属于单独交付验证。

## Validation Status

- 2026-06-04：模型设置页在 Vue dev server + Spring Boot 后端的浏览器环境中已由用户手测通过。
- 已验证进入模型设置页时 Active Chat / Active Embedding、左侧配置列表、右侧表单能正常回显。
- 已验证切换 `对话模型` 与 `Embedding 模型` 后，Temperature、默认 Top K、Embedding 维度等 role-specific 字段能按当前启用配置显示。
- 已移除模型设置区的整块加载遮罩，避免切换设置页时闪烁。
- 本阶段不执行桌面整包打包验证。

## Assumptions

- 第八阶段只做多模型配置，不做 SQLite 聊天记忆。
- 原计划 SQLite 聊天记忆顺延为第十二阶段。
- 每条模型配置独立保存 API Key，不做共享凭据表。
- Top K 保留为对话模型默认值；聊天输入区仍可临时覆盖。
- Embedding 模型切换后不自动重建索引，只提示用户手动重建。
- 旧 `/api/model-config` 保留一个阶段作为兼容接口；模型设置页使用 `/api/model-configs/settings...` 快照接口。
