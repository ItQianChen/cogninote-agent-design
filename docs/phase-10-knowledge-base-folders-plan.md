# CogniNote Agent 第十阶段任务计划：知识库目录管理与局部索引重建

## Summary

第十阶段改为调整知识库功能，原第十阶段“SQLite 聊天记忆”继续顺延；第十一阶段改做智能体模型运行时重构，SQLite 聊天记忆顺延为第十二阶段。

本阶段把当前“导入目录后只显示散落文档”的模式升级为“知识库文件夹”管理：用户通过系统文件夹选择器导入本地目录，目录记录写入 SQLite 并显示在知识库页；每个目录可启用/停用、删除、重新扫描并重建该目录索引；文档列表按目录折叠/展开展示。Lucene 仍是可重建索引，SQLite 仍是事实来源。

## Key Changes

- 新增知识库目录概念：
  - 新表 `knowledge_folders` 保存目录路径、显示名、递归扫描、启用状态、导入时间、索引时间、创建/更新时间。
  - `documents` 表新增 `knowledge_folder_id`，用于把文件级文档归属到导入目录。
  - 历史无归属文档不强行猜测目录，统一显示在“未归属文档”区域，用户可后续重新导入目录完成归属。
- 知识库目录行为：
  - 导入目录：创建或更新目录记录，扫描文件，写入/更新 `documents` 和 `chunks`，同步写入 Lucene。
  - 删除目录：删除目录记录及其关联文档/chunks，并清理 Lucene 索引；不删除用户本机原始文件。
  - 启用/停用：停用后该目录文档不参与搜索/RAG；实现上清理该目录 Lucene 条目但保留 SQLite 数据。重新启用后触发该目录重建索引。
  - 重建目录索引：只重新扫描对应本地目录并重建该目录下文档索引，不影响其他目录。
- 前端知识库页改为目录优先：
  - 顶部保留索引状态、全量重建、搜索入口。
  - 导入按钮优先打开系统文件夹选择器；浏览器开发环境保留手动路径输入作为 fallback。
  - 文档列表按知识库文件夹分组，每个文件夹支持展开/收起、启用/停用、重建、删除。
  - 默认索引地址文本调整为更清晰的主题 token，保证日间/夜间模式可读。
- 文档阶段顺延：
  - 原第十阶段“SQLite 聊天记忆”改为第十二阶段。
  - 新增第十一阶段“智能体模型运行时重构”，先整理 OpenAI-compatible 与 DashScope 调用层。
  - 同步更新 `docs/cogninote-agent-design.md`、`docs/phase-7-chat-ui-refactor-plan.md`、`docs/phase-8-multi-model-configuration-plan.md`、`docs/phase-9-ui-visual-readability-plan.md` 中的阶段引用。
  - README 只更新当前状态和文档入口，不塞入完整 API 细节。

## Public API / Schema Changes

新增 API：

```text
GET    /api/knowledge-folders
POST   /api/knowledge-folders/import
POST   /api/knowledge-folders/{id}/rebuild
PATCH  /api/knowledge-folders/{id}/enabled
DELETE /api/knowledge-folders/{id}
```

保留旧 API：

```text
GET    /api/documents
POST   /api/documents/ingest
DELETE /api/documents/{id}
POST   /api/index/rebuild
```

说明：

- `GET /api/documents`、`POST /api/documents/ingest`、`DELETE /api/documents/{id}` 暂时保留兼容。
- 新前端切到 `knowledge-folders` API，旧接口作为过渡入口。
- `POST /api/index/rebuild` 继续表示全量重建所有启用目录和未归属文档。
- `KnowledgeStore` 增加按文档集合重建的能力。
- 全量搜索和 RAG 检索只使用启用目录对应的索引内容。

桌面文件夹选择：

- Tauri 桌面环境新增文件夹选择命令，使用系统文件夹选择器。
- Web/dev 环境保留手动输入目录路径，避免浏览器限制导致功能不可测试。

## Implementation Notes

- 后端按现有三层结构落到 `controller / service / repository` 的知识库领域，不把目录逻辑塞进旧文档 Controller。
- 导入和重建目录要写清楚中文维护注释：SQLite 是事实来源、Lucene 可重建、停用目录为什么只清索引不删解析数据。
- 目录路径去重按规范化绝对路径处理，避免同一目录用 `D:\notes` 和 `D:/notes/` 重复导入。
- 单目录重建失败时保留旧 SQLite 数据，并把失败原因暴露到响应和日志；不影响其他知识库目录。
- 本地目录中的文件被删除后，单目录重建应删除应用内旧文档、chunks 和 Lucene 条目，但不触碰用户文件系统。
- 前端 Pinia 新增知识库目录 store，状态以 `folders -> documents` 为主，避免页面组件自己拼装复杂分组。
- UI 不引入大型组件库，使用现有样式 token 和 lucide 图标；列表区域必须可滚动，操作按钮保持可见且不撑开布局。

## Test Plan

后端：

```powershell
mvn test
```

重点覆盖：

- 目录导入和重复导入。
- 删除目录、启用/停用目录、单目录重建。
- 全量重建只处理启用目录和未归属文档。
- 删除目录不删除本机原始文件。
- 停用目录后搜索/RAG 不再命中该目录内容，重新启用并重建后恢复命中。
- 本地目录删除文件后，单目录重建清理应用内旧记录。

前端：

```powershell
npm --prefix cogniNote-agent-front run build
```

重点覆盖：

- 知识库页能显示目录列表、展开/收起文档、启用/停用、删除、单目录重建。
- 目录过多或文档过多时列表滚动正常，不撑坏设置页布局。
- 日间/夜间主题下索引地址、目录路径、状态文字都清晰可读。

不做桌面整包验证：

- 本阶段只验证功能和前端构建，不执行 Tauri/桌面安装包打包验收。

## Assumptions

- 第十阶段只做知识库目录管理，不做 SQLite 聊天记忆；第十一阶段先做智能体模型运行时重构，聊天记忆顺延为第十二阶段。
- 用户原始文件始终留在本地原路径，应用只保存目录路径、文档元数据、解析 chunks 和索引。
- 停用目录应立即从搜索/RAG 中消失，因此清理 Lucene 条目但保留 SQLite 解析数据。
- 历史散落文档不自动归属目录，避免错误猜测用户文件夹边界。
- 文件夹选择器优先服务桌面应用；浏览器开发态允许手动输入路径作为兼容 fallback。
