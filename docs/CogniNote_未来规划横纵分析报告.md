# CogniNote（知记空间）未来规划横纵分析报告
> 研究时间：2026-07-17 | 所属领域：本地优先个人知识库、RAG、Agent 桌面应用 | 研究对象类型：开源产品与工程项目

## 一、一句话定义

CogniNote 已经不是一个普通的 RAG Demo，而是一个具备桌面交付、文档摄入、混合检索、引用问答、知识健康诊断、OCR、知识图谱和 Agent 工具调用的本地知识工作台；它下一阶段最重要的任务，不是继续增加功能名词，而是把现有能力收敛成一个「可测量、可恢复、可被其他 Agent 调用、能安全写回知识」的本地知识上下文中枢。

## 二、核心判断

【值得继续做，而且已经有差异化基础。】

项目当前最有价值的资产不是 Java、Vue、Tauri 或某个模型 Provider，而是已经形成了一套清楚的数据观：用户原始文件是源头，SQLite 是业务事实来源，Lucene 是可重建索引，图谱是可删除的派生数据。这套边界比很多功能更重要，因为它决定了应用不会把用户知识锁进一个无法解释的向量库里。

问题也很明确。项目从 2026 年 6 月 2 日首次提交，到 7 月 12 日最新提交，在约四十天内完成了 173 次提交、36 个阶段计划和 0.1.70 版本。速度很快，功能面已经超过很多同体量个人项目，但代价是复杂度开始集中：后端有 1733 行的 `KnowledgeHealthService`、874 行的 `LuceneKnowledgeStore`、871 行的 `ModelConfigService`、852 行的 `DocumentIngestionService`；前端有多个接近或超过 900 行的页面、组件与 Store。现有完整后端测试运行 208 个测试时出现 2 个失败和 7 个错误，主要是 Lucene 写锁、SQLite 忙锁和测试数据串库；相关测试单独运行均通过，说明主要问题是测试隔离和资源生命周期，而不是核心功能完全失效。前端生产构建可以通过，但没有正式测试脚本，主入口约 1.46 MB、gzip 后约 468 KB，并出现无效动态导入和大 Chunk 警告。

因此，项目已经过了「证明能做出来」的阶段，正在进入「证明它长期可靠」的阶段。

我的推荐定位是：

> 一个让本地资料变得可搜索、可解释、可评测，并能被个人 Agent 安全读取和更新的本地知识上下文中枢。

这个定位刻意避开两个拥挤方向：不做又一个以模型数量为卖点的聊天壳，也不做需要多服务部署的企业级 RAG 平台。CogniNote 更适合占据中间那块仍不够成熟的位置：单用户桌面体验、数据在本机、检索过程可检查、知识生命周期可维护、Agent 操作可确认和撤销。

## 三、纵向分析：项目如何走到今天

### 3.1 第一阶段：先完成最小闭环，而不是搭空架构

项目最早的提交已经同时落下文档摄入、SQLite、Lucene 和知识库管理，紧接着完成混合检索与 RAG 对话。这条路径是对的。很多 Agent 项目先搭多层框架，却没有可靠数据；CogniNote 相反，先确定资料如何进入、如何被切分、如何检索、如何回读，再让模型回答。

这也形成了今天最稳固的架构约束：

```text
用户原始文件
  -> 解析与结构化失败诊断
  -> SQLite 文档、chunk 与业务状态
  -> Lucene BM25 / 向量索引
  -> RRF 混合排序
  -> SQLite 回读真实 chunk
  -> Agent 上下文与来源引用
```

其中 Lucene 已经不是简单关键词搜索。项目会为中文正文、代码标识符、路径、异常名、camelCase、snake_case 和 kebab-case 构造不同检索字段；混合检索扩大候选集后，使用带权 Reciprocal Rank Fusion 合并 BM25 与向量排名。后续路线图不应再把「实现 RRF」当新功能，而应把重点转到评测、二阶段重排和查询级诊断。

### 3.2 第二阶段：从能问答，走到能交付

随后加入 Tauri 桌面壳、Windows 打包、多会话、主题、多模型配置、macOS Apple Silicon 打包、自动更新和桌面会话令牌。这个阶段让项目从网页工程变成真实桌面产品。

Tauri 负责窗口、后端进程和更新，Spring Boot 承担业务 API 并托管 Vue 页面。这种结构的优势是保留 Java 生态、Spring AI 和 Lucene，同时获得桌面安装体验；代价是应用同时维护 Rust 壳、Java 后端、Vue 前端与平台打包脚本，发布验证成本天然高于纯 Electron 或纯 Web 产品。

现阶段 Windows x64 与 macOS arm64 已有安装产物，GitHub 仓库公开数据显示 19 Stars、1 Fork、3 个 Release。这个数字不大，却足以说明项目已经从个人代码实验进入早期公开验证。此时继续把精力主要放在新平台或更多皮肤上，价值不如把安装、升级、数据迁移和崩溃恢复做扎实。

### 3.3 第三阶段：从聊天功能，走到知识生命周期

项目中段最有产品价值的演进，是知识库目录管理、健康诊断、维护队列和失败诊断。

`knowledge_folder_runs` 不只是日志，而是维护任务与历史事实源；导入、同步、重新解析、补写索引、重建、启停和删除都进入 FIFO 队列，通过 SSE 展示状态。知识健康也没有额外持久化一份容易过期的快照，而是根据 SQLite、Lucene reader 统计和文件系统探针即时计算。

这套设计已经超过很多只做「上传文件 -> 生成 embedding」的桌面应用。它回答的是用户更真实的问题：

- 哪些资料已经变化但还没有同步？
- 哪些文档仍可使用旧索引，但最近同步失败？
- 哪些 PDF 需要 OCR？
- SQLite 与 Lucene 是否一致？
- 是否存在重复内容或疑似多版本冲突？
- 哪个维护任务正在运行，失败在哪一页、哪个阶段？

尤其是 PDF 页级 OCR 检查点值得保留并推广。它按文件哈希与执行签名恢复进度，单页成功就持久化，整份完成后才进入正式 chunks。这已经是一个耐久任务模型的雏形。

不过，知识维护队列目前仍有一个边界：排队记录写入 SQLite，但完整任务参数保存在进程内 `ConcurrentHashMap`。应用重启后，会清理中断任务；参数丢失的等待任务会被取消，而不是恢复执行。对导入和重建来说，这比完全丢状态好，但还不能称为真正的 durable run。下一阶段应把 OCR 检查点的思路推广到通用后台任务。

### 3.4 第四阶段：功能开始横向扩张

最近阶段加入知识图谱、追问补全 Agent、聊天片段引用、联网搜索 Tool Calling、更多文档格式和视觉模型 OCR。每个功能单独看都有价值，但它们开始形成「功能岛」：

- 知识图谱能抽取、合并、展示和回看证据，但还没有成为默认检索路径的一部分。
- 联网搜索能由模型调用，并与本地引用一起展示，但只有 Exa Provider，缺少统一的检索过程追踪与质量回归。
- 多 Agent 当前主要是普通聊天与知识库聊天的路由隔离，不是复杂多 Agent 协作；这恰好是优点，不应为了名义上的 Agent 化继续增加角色。
- 模型配置已经很完整，但继续增加 Provider 的边际价值正在降低。

这一阶段的核心矛盾已经从「能力不够」变成「能力之间还没有围绕同一用户任务形成主线」。

## 四、当前架构与数据结构判断

### 4.1 做得好的部分

#### 事实与派生数据边界清楚

SQLite 保存文档、chunks、聊天、模型配置、设置、任务、图谱事实和证据；Lucene 保存可重建的搜索字段与向量；原始文件不被应用改写。这是整个项目最值得坚持的设计。

#### 故障语义比较成熟

文档失败不只保存一句错误，而是区分扫描、读取、解析、OCR、模型配置、模型调用、切块、持久化和索引阶段。同步失败时可以保留旧的可用解析结果，避免一次临时故障把原有知识直接变成不可用。

#### 检索已经具备工程基础

项目已有 BM25、向量搜索、扩大候选集、带权 RRF、Embedding 限速与退避、索引一致性检查、代码友好字段。未来不需要为了「更现代」而整体迁移到 Qdrant。个人本地知识库规模下，SQLite + Lucene 仍然是低运维、可解释、可重建的好组合。

#### 用户操作有明确安全边界

删除知识库目录不会删除原始文件，删除图谱不会删除 chunks，维护操作有二次确认，运行中任务执行到安全完成点。这些设计比多一个 Agent 工具更能建立用户信任。

### 4.2 必须处理的复杂度热点

#### 后端巨型服务

`KnowledgeHealthService` 同时负责文件探针、运行记录分页、索引健康、内容重复、版本冲突、图谱新鲜度、严重级别和 DTO 组装。它现在可工作，但任何新诊断项都会继续扩大条件分支。

建议拆成：

- `DocumentAvailabilityProbe`：文件存在性、mtime、大小和目录新增文件。
- `IndexConsistencyProbe`：SQLite 与 Lucene 统计、一致性与 Embedding 可用性。
- `ContentQualityAnalyzer`：重复内容、版本冲突和噪音信号。
- `GraphFreshnessAnalyzer`：图谱派生数据是否过期。
- `KnowledgeHealthAggregator`：只组合领域结果并决定总体状态。

`LuceneKnowledgeStore` 可拆成索引写入、关键词召回、向量召回、融合排序、结果回读和诊断六个职责。`DocumentIngestionService` 可拆成扫描、元数据判断、解析、持久化、索引编排。`ModelConfigService` 可拆成配置 CRUD、Provider 归一化、角色策略和默认值策略。

这不是为了追求小文件，而是为了让检索评测、Parser 扩展和 Provider 扩展不再修改同一个高风险类。

#### 前端状态和视图过重

`knowledge-health-panel.vue`、`graph-explorer-view.vue`、`chat-view.vue`、`model-config.js`、`chat.js` 都接近或超过 900 行。页面逻辑、派生状态、异步编排、对话框和展示细节被放在同一处，已经影响测试和代码分割。

建议按用户任务拆分 composable 与领域组件，而不是只按视觉区域拆：

- 聊天：会话生命周期、流式消息、引用选择、上下文预算、工具事件。
- 知识健康：问题聚合、维护运行、问题详情、修复动作。
- 模型配置：角色切换、表单状态、模型目录、连接测试、激活策略。
- 图谱：清单、运行状态、视图加载、证据导航。

前端应补 Vitest 组件/Store 测试和 Playwright 关键路径测试。没有测试时，继续拆组件只会把复杂度从一个文件搬到多个文件。

#### 数据库升级策略不足

当前通过 MyBatis DDL 初始化表和幂等补列，`DatabaseSchemaInitializer` 明确把当前版本视为新基线，不负责完整历史测试版迁移。公开发布后，用户数据库不能再依赖「大致是当前结构」。

建议新增明确的 `schema_version` 与顺序迁移：

```text
V001 基础文档与 chunks
V002 模型多角色
V003 聊天记忆
V004 维护任务
V005 知识图谱
V006 OCR 检查点
...
```

每个 Release 必须测试：空库安装、上一个正式版升级、跨两个正式版升级、迁移失败回滚或安全停止。SQLite 不需要引入重型数据库平台，但必须有版本化迁移纪律。

#### 测试隔离未形成稳定基线

完整 `mvn test` 在 JDK 25 下运行 208 个测试，出现 Lucene `write.lock`、SQLite `SQLITE_BUSY` 和测试数据串库；`ChatControllerTests`、`DocumentIngestionServiceTests`、`KnowledgeFolderControllerTests` 单独运行均通过。这说明测试共享固定目录、应用上下文或资源关闭时机存在相互污染。

建议把「全量测试连续运行 20 次零失败」设为发布门槛，并完成：

- 每个测试上下文使用唯一 SQLite 与 Lucene 临时目录。
- 显式关闭 Lucene Directory、reader、writer 和连接池。
- 禁止测试使用长期固定 `target/test-*` 目录作为共享事实源。
- 为队列、索引重建和数据库清理增加串行资源控制。
- 增加真实旧库升级测试，而不只测空库初始化。

#### 前端包体已出现增长信号

生产构建通过，但主入口约 1.46 MB，gzip 后约 468 KB；Shiki、Mermaid、图谱和多种语法资源生成多个 500 KB 以上 Chunk，三个 Store 的动态导入因为同时被静态引用而失效。

建议：

- Markdown、Mermaid、图谱探索器按路由或实际消息类型懒加载。
- Shiki 只加载常用语言，其余按语言动态加载。
- 修正 Store 的伪动态导入。
- 设定 main entry gzip 小于 250 KB、首屏不加载图谱和全部高亮语言的预算。

## 五、横向分析：竞争格局

### 5.1 AnythingLLM Desktop：完整闭环的直接竞争者

AnythingLLM Desktop 的优势是安装后就能使用本地 LLM、RAG 和 Agent，并把 Agent Skill、MCP、定时任务和数据连接器放进同一产品。它证明了「单用户桌面 + 私有文档 + Agent」是成立的产品形态。

CogniNote 不应该追它的 Provider 和连接器数量。更有机会的方向是把检索与维护做得更透明：AnythingLLM 用户仍会遇到多文档和中文检索不稳定、向量库切换需要重嵌入、引用上下文不一致和诊断不足。CogniNote 已经有健康诊断、结构化失败、索引一致性和代码友好检索，应该把这些能力升级为真正的「检索实验室」。

### 5.2 Open WebUI：生态能力强，但配置膨胀

Open WebUI 支持多向量库、混合检索、cross-encoder rerank、外部知识源、MCP/OpenAPI、Arena 评测和 OpenTelemetry。它更像本地 AI 平台，而不是个人知识产品。

它给 CogniNote 的启示有两面。一面是知识库应成为模型可调用的一等工具：Agent 可以搜索、浏览文件、读取完整内容，而不是只在发送前勾选一个 RAG 开关。另一面是不要复制它庞大的管理面。CogniNote 的常用路径应该自动决定，复杂参数放进高级诊断区。

### 5.3 Khoj：更接近「个人第二大脑」

Khoj 把本地文件夹、Obsidian、Emacs、Web、桌面客户端、自动化任务和长期个人知识放在同一产品叙事里，还建立了公开质量评估流程。它的短板集中在同步延迟、桌面配置和权限颗粒度。

CogniNote 可以借鉴它的主动能力，但不应立即追移动端或通信渠道。更好的顺序是先做可靠文件监听、后台 digest、长期记忆候选和可恢复 Agent Run，再谈多终端。

### 5.4 RAGFlow：能力标杆，不是部署模板

RAGFlow 的 DeepDoc、结构化解析、Retrieval Test、reranker、工作流和 tracing 很强，但资源占用、部署复杂度和并发解析成本不适合单用户桌面应用。

CogniNote 应借鉴「可以检查解析、chunk、候选、排序和最终上下文」，而不是引入同等重量的服务架构。轻量本地检索实验室会成为明显差异点。

### 5.5 Cherry Studio：快速扩张带来的知识割裂

Cherry Studio 在多模型、MCP 和 Agent 生态上推进很快，但公开路线仍在补 Agent 主动搜索知识库、Agent 写入知识库、文件夹增量同步和 chunk embedding 复用。这说明「知识、文件、Agent 三套能力后期再打通」会付出很大代价。

CogniNote 已有统一的 document/chunk/source 模型，应趁现在把知识访问定义成稳定工具契约，不要先做独立 Skill 市场再回头补知识权限。

### 5.6 Obsidian Copilot：最值得参考的产品邻居

Obsidian Copilot 的价值在于源文件始终是普通 Markdown，关键词和结构搜索立即可用，语义索引只是增强；引用能返回真实笔记，项目配置也可以保存为 Markdown。这与 CogniNote 的「原始文件是源头、索引可重建」理念一致。

CogniNote 与它的差异不应是再造一个笔记编辑器，而应是对 Word、PDF、HTML、OCR、代码资料和知识维护更友好。未来若加入写回，也应以对源文件的显式 diff、预览、确认和撤销为核心。

## 六、2025-2026 技术趋势与项目取舍

### 6.1 RAG 评测已经从可选项变成基础设施

Ragas、Phoenix 和多个成熟产品都把数据集、实验、trace 和评测放到 Agent/RAG 工程中心。CogniNote 当前的健康诊断能回答「索引是否存在、文件是否变化」，却不能回答「这个问题是否召回了正确资料、引用是否支撑回答、改 Prompt 后是否退化」。

因此最高优先级不是再调一次 Top K，而是建立黄金问题集与回归机制。

### 6.2 MCP 值得提前，但应 Server 优先

MCP 已获得 Claude、ChatGPT、Gemini、Microsoft Copilot、Cursor、VS Code 等客户端支持。对 CogniNote 最有价值的第一步，是把自身变成只读知识服务，而不是先做可以安装几百个外部工具的客户端。

建议第一版只暴露：

- `search_knowledge`
- `get_document_chunk`
- `get_document_outline`
- `list_knowledge_folders`
- `get_source_evidence`
- `get_graph_neighbors`

所有结果返回稳定的 folder、document、chunk、页码、标题、score 和 evidence ID。写操作后置。

MCP 官方正在准备 2026-07-28 规范更新，候选方向包括 stateless core、server discovery 和多轮请求。实现时应使用官方 Java/Spring AI SDK 隔离协议变化，不自行绑定传输细节；稳定版发布前，先完成内部工具契约和只读 Server 验证。

### 6.3 长期记忆必须与聊天记录分层

当前 SQLite 聊天记忆、摘要和 Agent 类型隔离已经够用，但它们仍是会话历史，不等于长期记忆。长期记忆应该是用户可见、可编辑、可停用、可追溯的对象，例如用户偏好、项目术语、确认过的结论和未完成事项。

不要自动把所有聊天向量化。更好的方式是模型产生「记忆候选」，由用户确认后写入，并保留来源会话、置信度、更新时间和失效状态。

### 6.4 GraphRAG 应选择性使用

Microsoft GraphRAG 区分 Local、Global、DRIFT 和 Basic Search。CogniNote 已经有实体、关系、证据和视图，不需要复制完整框架，但可以按问题类型选择：

- 实体关系问题：图谱邻居 + 证据 chunk。
- 跨文档主题问题：社区摘要或分组摘要。
- 普通事实问题：继续 Lucene 混合检索。

图谱抽取成本高，也容易产生噪音。不能把每个问题都路由到图谱，也不应默认给所有目录生成全局社区报告。

### 6.5 多模态摄入需要统一中间表示

继续为每种扩展名直接增加一个 Parser，短期有效，长期会让表格、图片、公式和布局信息丢失。建议建立 block IR：

```text
Document
  -> Page / Slide / Sheet
  -> Block
     -> TEXT / HEADING / TABLE / IMAGE / CAPTION / EQUATION / CODE
     -> bbox / order / source locator
     -> parser / provider / version / confidence
```

近期格式优先级是 PPTX、XLSX、图片，再考虑 EPUB、ZIP 内文件和邮件附件。音频、视频不应进入近期主线。

### 6.6 Local-first 的下一步是可恢复，不是立刻做 CRDT

数据在 SQLite 只是本地存储；真正的 local-first 还要求一键导出、恢复验证、版本迁移、离线可用和供应商停止后数据仍可读。

CogniNote 当前没有正式备份/恢复功能，这比多设备实时同步更急。CRDT 只有在应用内多人编辑或多设备同时编辑同一笔记时才有价值。现在引入会制造新的事实来源和冲突语义。

## 七、重新排序后的未来路线图

### 阶段 A：0.1.x 稳定化版本，2-4 周

目标：让当前 36 个阶段真正成为可重复验证的产品基线。

#### A1. 测试与发布门禁

- 修复全量后端测试的 Lucene 锁、SQLite 忙锁和数据串库。
- 前端引入 Vitest，覆盖核心 Store 与数据归一化函数。
- Playwright 覆盖首次启动、模型配置、目录导入、搜索、RAG 问答、来源查看、维护任务和升级后启动。
- CI 强制后端测试、前端测试、生产构建和安装包 smoke test。
- 发布门槛：全量后端测试连续 20 次零失败，关键桌面路径三平台矩阵可追踪。

#### A2. 数据迁移与备份恢复

- 引入 `schema_version` 和顺序迁移。
- 生成带 manifest、schema 版本和文件哈希的便携备份包。
- 备份 SQLite、配置、聊天、维护历史和图谱事实；Lucene 默认不备份，恢复后重建。
- 恢复前做版本与空间预检，恢复后执行 SQLite integrity check、引用完整性检查和索引重建。

#### A3. 队列耐久化

- 把任务参数从内存 Map 持久化到 run payload。
- 增加 step、checkpoint、attempt、lease 和 idempotency key。
- 应用重启后恢复可恢复任务；不可恢复任务明确标记 `INTERRUPTED`，允许从安全点重试。
- 统一 OCR、目录维护和图谱生成的运行模型。

#### A4. 前端性能与复杂度收敛

- 拆分五个超大页面/Store。
- Mermaid、Shiki、图谱资源按需加载。
- 修复无效动态导入。
- 设定首屏包体预算和构建回归阈值。

这一阶段不新增大功能。它决定后续每个功能是资产还是负担。

### 阶段 B：0.2 可测量知识引擎，4-8 周

目标：把「检索感觉不错」变成「可以证明检索和回答没有退化」。

#### B1. 黄金评测集

- 支持创建本地评测集，第一批 50-100 个问题。
- 每题保存期望命中文档/chunk、允许答案要点、不可回答条件和标签。
- 标签至少区分中文、代码、路径、错误码、跨文档、表格和 OCR。

#### B2. 检索实验室

- 展示原始问题和追问补全后的 retrieval query。
- 展示 BM25 候选、向量候选、RRF 排名与各自分数。
- 展示去重、相邻 chunk 合并和最终上下文。
- 展示解析、embedding、检索、重排、模型首 token 和总耗时。
- 支持保存一次实验配置并与另一配置对比。

#### B3. 可选二阶段 reranker

- 保留现有 Lucene + RRF。
- 对 Top 20-50 候选提供可选 cross-encoder 或 Provider rerank。
- 是否默认启用由 Recall@K、MRR/NDCG、引用命中率和延迟评测决定。
- 不把 Qdrant 适配作为这一阶段前置条件。

#### B4. 引用可信度

- 引用精确到文件、标题、页码、段落或行。
- 检查回答中的关键事实是否能映射到来源证据。
- 增加「未找到足够依据」与「仅来自网页」的清晰状态。
- 提供用户反馈：引用正确、引用不支持、遗漏重要来源。

#### B5. 统一文档 Block IR

- 先迁移现有 Markdown、HTML、DOCX、PDF Parser 输出。
- 增加 PPTX、XLSX 和图片。
- 表格、公式、图注保留源定位，避免全部压平成普通文本。

### 阶段 C：0.3 本地知识上下文中枢，6-10 周

目标：让 CogniNote 不只在自己的聊天页里有用。

#### C1. MCP 只读 Server

- 通过本地 STDIO 或受控 loopback 方式暴露知识查询。
- 每个工具有明确 schema、超时、结果上限和审计记录。
- 默认只读，不自动暴露原始完整文件。
- 支持按目录授权和临时会话范围。

#### C2. 知识库成为 Agent 一等工具

- Agent 可先浏览目录、搜索、读取 outline、再读取具体 chunk。
- 对小文件可选择全文上下文，对大集合使用检索。
- 工具选择遵守上下文预算，避免把所有工具定义和所有结果塞入 128K。

#### C3. 可选文件监听与可靠增量摄入

- 使用文件系统事件作为提示，不把事件本身当事实。
- 防抖后扫描并用 hash 校验新增、修改和删除。
- 大规模变化先生成变更计划，用户确认后执行。
- chunk 级 embedding 复用，内容未变不重复调用模型。

#### C4. 分层记忆与上下文预算器

- 区分 Session、State、Long-term Memory。
- 长期记忆默认先生成候选，再由用户确认。
- 给系统规则、当前问题、会话历史、长期记忆、本地证据、网页结果、图谱证据和回答预留分别分配预算。
- 去除重复证据、裁剪旧工具结果、优先保留可引用事实。

#### C5. 通用后台 Agent Run

- 支持批量摘要、每周 digest、重复/版本冲突分析、图谱更新和主题研究。
- 每个 Run 保存 step、artifact、checkpoint、usage 和 approval。
- 有模型调用次数、联网次数、token 和时间预算。

### 阶段 D：0.4 安全知识构建，8-12 周

目标：从「读取我的资料」走到「帮助我整理资料」，但不破坏用户源文件。

#### D1. 写回协议

- Agent 生成修改建议，不直接静默写文件。
- UI 展示目标文件、diff、来源证据和影响范围。
- 用户确认后写入，并保存操作历史和可撤销补丁。
- 写操作按目录授权，可全局关闭。

#### D2. 知识构建功能

- 从聊天或文档生成结构化知识条目。
- 标签、双向链接、项目术语和行动项建议。
- 新资料 digest、主题综述、冲突资料对照。
- 输出仍优先使用 Markdown 等开放格式。

#### D3. 选择性图谱检索

- 实体关系问题使用图谱邻居和证据。
- 全局主题问题使用分组摘要。
- 普通问题保持 Lucene 路径。
- UI 显示本轮用了哪种检索，并能回到原始证据。

#### D4. Obsidian/Markdown 深度集成

- 不复制 Vault 作为第二份事实源。
- 支持目录范围、Frontmatter、标签、链接和附件语义。
- 写回沿用 diff、确认和撤销协议。

### 阶段 E：1.0 公开稳定版，3-6 个月

目标：把个人项目成熟度提升为用户敢长期存放使用记录的桌面产品。

- Windows、macOS Apple Silicon、Intel/Universal 和 Linux 的明确支持矩阵。
- 签名、公证、自动更新回滚和正式升级验收。
- 可选同步：先同步开放源文件、设置和事实数据，索引默认本地重建。
- MCP Client 与有限 Skill 插件机制，先白名单和显式安装，不自动发现未知 Server。
- 本地诊断包导出，自动脱敏模型请求、路径和配置中的敏感字段。
- 稳定的扩展契约：Parser、Search Provider、Reranker、Tool、Artifact Exporter。

## 八、功能优先级清单

| 优先级 | 功能 | 原因 |
|---|---|---|
| P0 | 全量测试隔离与发布门禁 | 当前完整测试不能稳定通过，继续加功能会放大回归风险 |
| P0 | schema 版本迁移 | 已有公开 Release，用户升级数据必须有确定性 |
| P0 | 一键备份、恢复与完整性检查 | local-first 的基础能力，当前完全缺失 |
| P0 | RAG 黄金评测集与检索追踪 | 当前健康诊断不等于检索质量诊断 |
| P0 | 队列任务参数与检查点持久化 | 当前等待任务参数仍依赖内存，重启不能真正恢复 |
| P0 | 前端测试和按需加载 | 前端巨型 Store/组件与包体已成为扩展风险 |
| P1 | 可选 reranker | 现有 RRF 已完成，下一步是评测驱动的二阶段排序 |
| P1 | 统一 Block IR 与 PPTX/XLSX/图片 | 防止 Parser 越加越多、结构信息持续丢失 |
| P1 | MCP 只读 Server | 把 CogniNote 变成其他 Agent 可用的本地知识层 |
| P1 | 文件监听与 chunk embedding 复用 | 降低手动同步成本，同时保留显式变更计划 |
| P1 | 上下文预算与分层长期记忆 | 比继续增加默认上下文窗口更能提升长期对话质量 |
| P1 | 通用 Agent Run | 复用现有队列与 OCR 检查点，承载后台知识任务 |
| P2 | 安全写回、diff、撤销 | 从问答升级为知识整理，但必须守住源文件边界 |
| P2 | 选择性 GraphRAG | 让已有图谱产生问答价值，而不是继续做展示功能 |
| P2 | Obsidian 深度集成 | 与本地 Markdown 用户群高度匹配，但应在写回协议之后 |
| P3 | Qdrant 适配 | 可保留 SPI 与实验支持，不应成为 0.2 核心目标 |
| P3 | 更多搜索/OCR Provider | 只有在统一 Provider 契约和真实用户需求后再扩展 |
| P3 | 托盘、更多皮肤 | 属于完善项，不是下一阶段产品主线 |

## 九、明确不建议近期做的事

- 不要为了宣传「多 Agent」增加大量角色型 Agent。
- 不要把每个问题都交给多个 Agent 讨论。
- 不要默认把所有聊天写入长期记忆。
- 不要默认对每个问题运行 GraphRAG。
- 不要为了向量检索把 SQLite + Lucene 整体迁移到外部向量数据库。
- 不要在没有黄金评测集前持续微调 Top K、权重和 Prompt，然后只凭主观体验判断。
- 不要先做开放插件市场，再补工具权限、超时、取消和卸载清理。
- 不要在没有应用内多人编辑需求前引入 CRDT。
- 不要把视频、语音助手、通用电脑操作作为近期主线。
- 不要把「支持更多模型」当作核心卖点。

## 十、关于本地 API Key 的判断

本报告不把「模型 API Key 明文保存在本机 SQLite」列为项目当前 P0。对于单用户、本地桌面、数据库不上传、不通过 API 回显、日志不记录密钥的应用，这与把密钥保存在普通本地配置文件属于同类信任边界，不应机械地当作远程泄露漏洞。

真正更现实的风险是用户误把整个数据目录同步到公开位置、诊断包或日志意外包含凭据、备份包没有边界说明。未来公开稳定版可以提供系统凭据库作为可选增强，但它不应挤占测试隔离、迁移、备份恢复和 RAG 质量评测的优先级。

## 十一、衡量路线是否成功

### 工程可靠性

- 后端全量测试连续 20 次零失败。
- 前端核心 Store 与关键页面有稳定测试。
- 上一个正式版升级成功率 100%，失败不会破坏原库。
- 中断的可恢复任务重启后能从检查点继续。
- 备份恢复后 SQLite 完整性、引用关系和索引重建全部通过。

### 检索与回答质量

- 黄金集 Recall@5、Recall@10、MRR/NDCG 有版本记录。
- 引用命中率、引用支持率和无依据回答率可追踪。
- 中文、代码、路径、错误码和跨文档问题分别统计。
- reranker 只有在质量收益显著且延迟可接受时默认启用。

### 用户体验

- 新用户从安装到第一次带引用回答的步骤明显减少。
- 文件变化能被提示并形成清晰的同步计划。
- 用户能在一次点击内看到「为什么命中这些资料」。
- Agent 的任何写回都有预览、确认、历史和撤销。

### 性能与交付

- 首屏不加载图谱和全部代码高亮语言。
- 主入口 gzip 控制在 250 KB 左右，重资源按需加载。
- 关键词搜索与本地结果回读维持交互级延迟；混合检索和 rerank 分阶段展示耗时。
- 每个正式 Release 有安装、升级、卸载和数据保留报告。

## 十二、未来三种剧本

### 最可能的剧本

CogniNote 继续保持本地单用户定位，在 0.2 阶段补齐评测、备份、迁移和检索实验室，在 0.3 阶段推出 MCP 只读服务。它不会成为用户数量最大的聊天客户端，却会成为少数真正能解释知识状态、检索过程和来源证据的桌面知识工具。这条路最符合现有架构积累。

### 最危险的剧本

项目继续按「每个阶段增加一个显眼功能」推进：Qdrant、更多 Provider、更多 Agent、更多图谱视图、更多平台同时展开。巨型服务和前端 Store 继续膨胀，完整测试长期偶发失败，数据库升级依赖当前结构，最终每个新功能都要触碰多个高风险模块。外观看起来越来越完整，维护速度却突然下降。

### 最乐观的剧本

CogniNote 把知识健康、检索实验室、精确引用、MCP 和安全写回结合起来，形成一个其他产品尚未很好覆盖的品类：本地、开放格式、可验证、可被多个 Agent 复用的个人知识上下文层。聊天页只是一个入口，Claude、Codex、VS Code、Obsidian 和未来的个人工作流都可以在用户授权范围内调用同一份知识。

这里的护城河不是模型，也不是某个向量库。

是用户积累多年后，仍然知道资料在哪里、为什么被检索、谁修改过、能否恢复，以及换一个 Agent 后是否还能继续使用。

## 十三、信息来源

以下网页统一访问于 2026-07-17。GitHub Issue 代表公开用户报告或设计讨论，不代表所有用户都会遇到同一问题。

### CogniNote 项目事实

- [CogniNote GitHub 仓库](https://github.com/ItQianChen/cogninote-agent-design)
- 本地仓库 README、项目方案、阶段文档、Git 历史、Java/Vue/Tauri 源码与测试报告

### 竞品

- [AnythingLLM 官方仓库](https://github.com/Mintplex-Labs/anything-llm)
- [AnythingLLM Desktop 概览](https://docs.anythingllm.com/installation-desktop/overview)
- [AnythingLLM Agent 设置](https://docs.anythingllm.com/agent/setup)
- [Open WebUI 官方仓库](https://github.com/open-webui/open-webui)
- [Open WebUI RAG 文档](https://docs.openwebui.com/features/chat-conversations/rag/)
- [Open WebUI Administration 与 Evaluation](https://docs.openwebui.com/features/administration/)
- [Khoj 官方仓库](https://github.com/khoj-ai/khoj)
- [Khoj 搜索架构](https://docs.khoj.dev/features/search)
- [RAGFlow 官方仓库](https://github.com/infiniflow/ragflow)
- [RAGFlow Retrieval Test](https://ragflow.io/docs/run_retrieval_test)
- [Cherry Studio 官方仓库](https://github.com/CherryHQ/cherry-studio)
- [Obsidian Copilot 官方仓库](https://github.com/logancyang/obsidian-copilot)
- [Obsidian Copilot Vault QA](https://www.obsidiancopilot.com/en/docs/vault-qa)

### 技术趋势

- [MCP 架构概览](https://modelcontextprotocol.io/docs/learn/architecture)
- [MCP Server 概念](https://modelcontextprotocol.io/docs/learn/server-concepts)
- [MCP 2026 Roadmap](https://modelcontextprotocol.io/development/roadmap)
- [MCP Java SDK](https://java.sdk.modelcontextprotocol.io/latest/)
- [Spring AI MCP 概览](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Ragas Faithfulness](https://docs.ragas.io/en/stable/concepts/metrics/available_metrics/faithfulness/)
- [Arize Phoenix](https://arize.com/docs/phoenix)
- [Phoenix Spring AI Tracing](https://arize.com/docs/phoenix/integrations/java/springai/springai-tracing)
- [Azure Hybrid Search](https://learn.microsoft.com/en-us/azure/search/hybrid-search-overview)
- [Azure RRF 排名](https://learn.microsoft.com/en-us/azure/search/hybrid-search-ranking)
- [Qdrant Hybrid Search](https://qdrant.tech/articles/hybrid-search/)
- [Microsoft GraphRAG Query Overview](https://microsoft.github.io/graphrag/query/overview)
- [Microsoft GraphRAG Local Search](https://microsoft.github.io/graphrag/query/local_search)
- [Anthropic Effective Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- [Google ADK Session、State 与 Memory](https://google.github.io/adk-docs/sessions/)
- [Local-First Software 论文](https://www.inkandswitch.com/local-first/static/local-first.pdf)
- [Automerge 3.0](https://automerge.org/blog/automerge-3/)
- [Microsoft MarkItDown](https://github.com/microsoft/markitdown/blob/main/README.md)
- [Mistral Document Processing](https://docs.mistral.ai/studio-api/document-processing/overview)
- [OpenAI Background Mode](https://developers.openai.com/api/docs/guides/background)
- [Temporal Spring AI Integration](https://docs.temporal.io/develop/java/integrations/spring-ai)

## 十四、方法论说明

本报告使用横纵分析法：纵向追踪 CogniNote 从最小 RAG 闭环到桌面交付、知识维护、Agent 与 OCR 的演进；横向对比当前本地知识库、个人 AI 和企业 RAG 产品，并将两条轴交叉，用于判断哪些优势来自既有架构、哪些短板来自快速扩张，以及未来功能应如何重新排序。
