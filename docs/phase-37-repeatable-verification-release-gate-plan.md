# 第 37 阶段计划：可重复验证与发布门禁

> 实施状态（2026-07-26）：代码与本地验证链已落地。后端单次 208 tests 通过，并完成连续 5 轮零失败；发布候选仍需由 `repeatability.yml` 在同一提交上补齐 20 轮记录。PR required checks 稳定一周、Windows/macOS 安装 smoke 实跑记录及 macOS/portable 体积基线仍属于仓库设置和发布候选退出条件，不能由本地 Windows 实现伪造。

## Summary

第 37 阶段落实《CogniNote 未来规划横纵分析报告》中阶段 A 的第一优先级：把当前已经完成的 36 个阶段固化为可重复验证、可以阻止回归进入发布流程的产品基线。

本阶段不新增面向用户的大功能，也不启动 Rust 重写、纯 Java Web 化或大规模前端重构。核心业务仍由 Spring Boot 提供本地 loopback 服务，浏览器自动化直接验证 Web UI；当前 Tauri 桌面壳只承担安装、启动、升级和系统集成 smoke。这样既验证用户真实使用路径，也不会把业务正确性绑定在桌面壳实现上。

阶段 37 完成后，任何提交和桌面发布都必须有可追踪的验证结果：

1. 后端测试使用隔离存储，能够重复执行而不串库、不残留 Lucene 锁。
2. 前端具备 Vitest 基础设施，核心归一化和 Store 状态转换有单元测试。
3. Playwright 覆盖首次启动、配置、导入、搜索、RAG 来源和维护任务等关键路径。
4. Pull Request 必须通过后端测试、前端测试、生产构建和浏览器 smoke。
5. Windows/macOS 发布工作流只能在验证工作流通过后打包，不能再无条件跳过测试。
6. 前端 bundle 和桌面安装包建立可审查的体积基线与回归阈值。

## Core Judgment

报告中的阶段 A 同时包含测试门禁、数据库迁移、备份恢复、任务耐久化和前端复杂度治理。如果一次迭代同时改这四类基础设施，失败时很难判断是测试环境、迁移、队列恢复还是前端拆分引起，发布风险反而会扩大。

因此阶段 A 拆成四个连续里程碑：

- 第 37 阶段：可重复验证与发布门禁。
- 第 38 阶段：数据迁移与备份恢复。
- 第 39 阶段：通用耐久任务模型。
- 第 40 阶段：前端拆分与性能收敛。

第 37 阶段只建设后续三个阶段共同依赖的验证地基。数据库 schema、维护任务 payload 和超大 Vue/Store 文件在本阶段保持业务行为不变。

## Current Baseline

截至 2026-07-19，项目基线如下：

- 后端共有 50 个 Java 测试文件；全量执行结果为 208 tests、0 failures、0 errors，单次约 51 秒。
- 多个 Spring 集成测试把 `app.storage.base-dir` 和 `app.storage.database-path` 固定到 `target/test-cogninote-*`，不同运行、异常退出或并行执行时可能复用旧 SQLite/Lucene 数据。
- parser 类测试已经普遍使用 `@TempDir`，说明 JUnit 临时目录方案与当前项目兼容。
- 前端没有正式测试文件，`package.json` 没有 `test` 脚本，也没有 Vitest、Vue Test Utils 或 Playwright 依赖。
- 前端生产构建通过，但主 bundle 约为 1.456 MB / 468 KB gzip，并持续出现超过 500 KB 的 chunk 警告。
- `knowledge-maintenance.js` 第 289-291 行的动态导入对应模块在其他入口已被静态导入，当前不能形成有效代码分块。
- Windows 发布工作流调用 `build-desktop-app.ps1 -SkipTests`，macOS 工作流调用 `build-desktop-app-macos.sh --skip-tests`。
- `scripts/smoke-knowledge-health.ps1` 已存在，但没有接入 Pull Request 或发布 CI。
- 当前没有独立的 Pull Request 验证工作流，也没有 bundle/安装包体积回归门禁。

上述事实说明问题不是“完全没有测试”，而是测试结果还没有成为可靠、强制、可重复的发布条件。

## Goals

- 消除集成测试对固定测试数据目录的依赖。
- 保证测试成功、失败和中断后都能关闭 SQLite、Lucene reader/writer 和后台 executor。
- 建立前端单元测试最小闭环，并优先覆盖高变更频率的数据归一化逻辑。
- 建立不依赖真实模型、真实 API Key、外网和用户目录的浏览器端关键路径 smoke。
- 为 Pull Request、主分支和桌面发布建立统一验证工作流。
- 让“跳过重复测试”只能发生在上游验证已成功的打包 job 中，而不是成为发布入口的默认行为。
- 将构建产物体积变化变成机器可读、可比较、可阻断的指标。
- 留下一次后端全量测试连续 20 次零失败的可审查记录。

## Non-goals

- 不引入 `schema_version`，不做数据库迁移框架。
- 不实现备份包、恢复流程或 SQLite/Lucene 恢复校验。
- 不改造维护、OCR、图谱任务的 payload、lease、checkpoint 和重启恢复。
- 不拆分 `KnowledgeHealthService.java`、`chat.js`、`model-config.js` 或超大 Vue 页面。
- 不在本阶段解决 Mermaid、Shiki、图谱模块的按需加载。
- 不为了测试把整个应用重构成新架构。
- 不把生产模型调用或任何真实第三方服务接入 CI。
- 不要求每个 Pull Request 构建和安装完整 Windows/macOS 安装包；安装包 smoke 放在发布工作流，PR 只执行核心验证。
- 不新增 Linux 桌面安装包。Linux runner 只用于验证“Spring Boot + 浏览器”核心路径的可移植性。
- 不讨论 API Key 是否明文回显。本阶段只要求测试、日志、artifact 和 CI 输出不得包含用户配置值。

## 37-1：后端测试存储隔离

### 目标

让每个拥有 SQLite/Lucene 状态的测试类使用唯一临时目录，重复执行、并行执行和上一次失败都不会污染下一次结果。

### 实现方案

第一批改造所有包含以下固定配置的测试：

```text
app.storage.base-dir=target/test-cogninote-*
app.storage.database-path=target/test-cogninote-*/cogninote.db
```

每个测试类采用类级 `@TempDir` 和 `@DynamicPropertySource`：

```java
@TempDir
static Path storageRoot;

@DynamicPropertySource
static void registerStorageProperties(DynamicPropertyRegistry registry) {
    registry.add("app.storage.base-dir", () -> storageRoot.toString());
    registry.add(
        "app.storage.database-path",
        () -> storageRoot.resolve("data/cogninote.db").toString()
    );
}
```

实施约束：

- 先逐类采用明确配置，不一开始创建复杂 JUnit 扩展。
- 三个以上测试类稳定使用同一模式后，再抽取小型测试 helper；helper 只负责路径注册，不隐藏测试数据准备。
- 测试 fixture 文件仍使用实例级 `@TempDir`，应用存储使用类级临时目录，避免 Spring Context 初始化早于实例字段。
- 测试不得读取或删除真实用户目录、默认 `%APPDATA%/CogniNote` 或 `~/Library/Application Support/CogniNote`。
- 不用固定端口；需要 HTTP server 的测试使用随机端口。
- 不依赖测试执行顺序，不通过上一个测试创建的数据作为下一个测试前置条件。

### 资源关闭

- 检查所有 Lucene `Directory`、`IndexWriter`、`DirectoryReader` 和自建 executor 是否由 Spring Bean 生命周期关闭。
- Bean 持有资源时实现 `AutoCloseable`、`@PreDestroy` 或显式 destroy method，测试不直接反射关闭内部字段。
- 只有确实持有独占资源且无法安全复用 Context 的测试类使用 `@DirtiesContext(classMode = AFTER_CLASS)`；不得对所有测试无差别启用，避免测试时间失控。
- 增加“Context 关闭后可重新打开同一路径”的专项测试，直接验证 Lucene 锁已释放。
- Windows 连续运行是主要判据，因为文件锁和删除失败在 Windows 上最容易暴露。

### 第一批目标测试

- `DocumentIngestionServiceTests`
- `KnowledgeFolderControllerTests`
- `KnowledgeHealthControllerTests`
- `KnowledgeMaintenanceControllerTests`
- `SearchIndexIntegrationTests`
- `SearchVectorIntegrationTests`
- `ChatControllerTests`
- `ChatSessionRepositoryTests`
- `ModelConfigServiceTests`
- `ModelConfigControllerTests`
- `DocumentControllerTests`
- `DesktopSessionTokenFilterTests`

### 重复执行工具

新增 `scripts/repeat-backend-tests.ps1`：

- 参数：`-Iterations`，默认 5，发布候选使用 20。
- 每轮记录开始时间、结束时间、耗时、测试数和退出码。
- 每轮必须存在 Surefire XML 且至少执行一个非 skipped 测试，禁止把零测试误记为通过。
- 任一轮失败立即停止，并保留该轮 Surefire report。
- 输出机器可读的 `artifacts/test-repeatability/backend-repeatability.json`。
- 脚本不删除用户目录，只清理 Maven 标准测试输出和本次运行创建的临时目录。

## 37-2：前端 Vitest 基础设施

### 依赖与配置

在 `cogniNote-agent-front` 增加：

- `vitest`
- `@vue/test-utils`
- `happy-dom`

新增脚本：

```json
{
  "test": "vitest run",
  "test:watch": "vitest",
  "test:coverage": "vitest run --coverage"
}
```

首期不把覆盖率百分比设为发布硬门槛。覆盖率报告用于发现空白区域；发布门禁只要求测试全部通过，避免为了数字制造低价值测试。完成首批用例后再记录基线，并规定新增或修改的核心模块不得降低对应文件覆盖率。

配置原则：

- 使用独立 `vitest.config.js`，避免测试启动被 Vite DevTools 和生产构建插件影响。
- `src/test/setup.js` 统一安装 Pinia、清理 DOM、恢复 mock 和 localStorage。
- API 调用在 Store 单元测试中使用模块 mock，不发真实 HTTP 请求。
- 时间、UUID、浏览器通知和 Tauri runtime 必须可注入或 mock，测试不得依赖本机状态。

### 首批测试对象

优先测试纯逻辑和用户状态转换，而不是对大页面做脆弱的整页 snapshot：

1. `utils/knowledge-health-issues.js`
   - issue 分组、忽略键、示例归一化、图谱修复动作判断。
2. `utils/document-failures.js`
   - 失败阶段、进度文本、技术详情和按阶段分组。
3. `config/settings-navigation.js`
   - alias、未知 item 和默认设置项归一化。
4. `config/knowledge-navigation.js`
   - 面板 ID 和默认值归一化。
5. `stores/chat.js`
   - session/message/source/reference 归一化。
   - SSE source 合并去重。
   - `useKnowledgeBase`、`useWebSearch`、`topK` 边界值。
   - stop/error/done 状态转换。
6. `stores/knowledge-maintenance.js`
   - 同一时刻只进行一次 snapshot refresh。
   - run 完成后刷新 queue/folder/health/search。
   - stream 断开和取消不会留下错误订阅状态。
7. `stores/model-config.js` 与 `stores/web-search-settings.js`
   - 远端设置归一化不覆盖用户正在输入的草稿。
   - 保存成功、失败和测试连接的 loading/error 状态恢复。

如果 `chat.js` 内部归一化函数无法直接测试，只允许把这些无副作用函数移动到 `src/domain/chat-normalizers.js`；不借此拆分整个 Chat Store。

## 37-3：Playwright 确定性关键路径

### 测试边界

Playwright 验证用户从浏览器看到的完整交互。Spring Boot 使用本次运行的临时数据目录启动，前端使用 Vite 或已构建静态资源。所有 fixture 位于仓库测试目录，不读取用户真实文件。

模型配置和 RAG 流程不得访问真实模型：

- 模型配置的保存/读取可以调用真实本地后端。
- “测试连接”请求由 Playwright route mock 返回确定结果。
- RAG 的流式回答接口由 route mock 返回固定 SSE 序列，其中包含 token、source 和 done 事件。
- 文档导入、关键词搜索、健康诊断和维护任务尽量调用真实 Spring Boot/SQLite/Lucene 链路。
- 外网搜索默认关闭；任何测试都不得要求 API Key、联网额度或第三方账号。

### 首批 smoke 场景

1. 首次启动
   - 使用空临时目录启动。
   - 系统状态可见，默认导航可用，无旧用户数据。
2. 模型配置
   - 新建、编辑、保存和重新加载本地配置。
   - mock 测试连接成功与失败，并验证错误提示可恢复。
3. 目录导入
   - 导入固定 Markdown/TXT/HTML 小目录。
   - 等待导入结束，验证文档数和目录摘要。
4. 搜索与来源
   - 搜索 fixture 中的唯一关键词。
   - 打开结果和来源详情，验证文件名、片段和定位信息。
5. RAG 问答
   - 发送问题，接收固定 SSE。
   - 验证流式文本、来源列表和会话重新加载后的来源恢复。
6. 维护任务
   - 对 fixture 目录执行同步或补写索引。
   - 验证 queued/running/completed 状态和最终健康摘要。
7. 重启持久化
   - 使用同一临时目录停止并重新启动后端。
   - 验证目录、文档、会话和配置仍存在。

“升级后启动”不伪装成普通浏览器测试：真正的 N-1 到当前版本覆盖安装放在发布安装包 smoke 中。浏览器 smoke 只负责验证核心数据在进程重启后可读取。

### 项目结构

```text
cogniNote-agent-front/
  playwright.config.js
  e2e/
    fixtures/
      knowledge-base/
    first-run.spec.js
    model-config.spec.js
    knowledge-flow.spec.js
    rag-sources.spec.js
    maintenance.spec.js
    restart-persistence.spec.js
```

默认 PR 只跑 Chromium。Firefox/WebKit 不作为本地桌面技术栈的正确性代理；跨平台差异由 Windows WebView2 和 macOS WKWebView 安装包 smoke 负责。

## 37-4：统一 CI 验证工作流

### 新增 `verify.yml`

新增 `.github/workflows/verify.yml`，同时支持：

- `pull_request`
- 主分支 `push`
- `workflow_dispatch`
- `workflow_call`

建议 jobs：

```text
backend-tests
  JDK 25 + Maven cache
  mvn -B test

frontend-tests
  Node 22 + npm cache
  npm ci
  npm run test

frontend-build
  needs: frontend-tests
  npm run build
  node scripts/check-frontend-bundle-budget.mjs

browser-smoke
  needs: [backend-tests, frontend-build]
  install Playwright Chromium
  start temporary Spring Boot + Vite/static frontend
  npm run test:e2e

knowledge-smoke
  needs: backend-tests
  scripts/smoke-knowledge-health.ps1
```

仓库保护规则把上述 jobs 设为 Required checks。任何一个失败都不能合并到发布分支。

### 重复性工作流

新增 `.github/workflows/repeatability.yml`：

- `workflow_dispatch` 可指定 5/10/20 次。
- 每周定时在 `windows-latest` 运行 5 次，尽早发现锁和残留问题。
- 发布候选手动或由 release workflow 运行 20 次。
- 上传 Surefire reports 和 `backend-repeatability.json`。

Pull Request 不执行 20 次全量测试，否则每个小改动都付出约 17 分钟以上的重复成本。发布门槛是“候选提交有一次可追踪的连续 20 次零失败记录”，不是“每次 PR 都跑 20 次”。

### 发布工作流依赖

Windows/macOS workflow 增加 reusable verification job：

```yaml
jobs:
  verify:
    uses: ./.github/workflows/verify.yml

  build-windows:
    needs: verify
```

macOS 同理。

打包脚本可以在 `verify` 已通过后使用 `SkipTests/--skip-tests` 避免同一 workflow 重复执行 Maven 测试，但必须满足：

- skip 参数只出现在 `needs: verify` 的 packaging job。
- 手动发布入口不能绕过 verify。
- 本地开发者直接运行构建脚本时默认仍执行测试。
- workflow 日志明确写出“tests skipped because reusable verification job succeeded”，不能静默跳过。

这解决的是“无条件跳过测试”，而不是机械删除所有 skip 参数后重复消耗 CI 时间。

## 37-5：桌面安装包 smoke

### 通用产物检查

每个平台打包完成后必须验证：

- 预期安装包、portable/app bundle 和内嵌 backend 存在。
- 内嵌 backend 能启动，并在限定时间内返回 `/api/system/status`。
- 静态首页和一个 `/assets/**` 资源可读取。
- status 中版本号与 workflow 版本一致。
- 进程可正常停止，没有遗留 backend 进程占用产物目录。
- artifact 不包含测试数据库、测试 fixture、`.env`、日志或本地用户配置。
- 签名模式下继续执行现有 Windows Authenticode、macOS codesign/notary/stapler 验证。

### Windows

- portable zip 解压后启动 backend smoke。
- NSIS 使用静默模式安装到 runner 临时目录。
- 启动当前版本，验证进程和系统状态，再静默卸载。
- release candidate 增加 N-1 安装后覆盖安装当前版本，确认版本变化且 `%APPDATA%/CogniNote` 测试数据保留。
- 所有测试使用专用临时 `APPDATA/LOCALAPPDATA`，不得碰 runner 或开发机真实配置。

### macOS

- 挂载 DMG，检查 `.app` 和嵌套 `CogniNoteBackend.app`。
- 将 app 复制到 runner 临时 Applications 目录，不覆盖系统 `/Applications`。
- 验证可执行文件、资源、版本和签名；环境允许时启动并轮询 status。
- release candidate 记录 N-1 app 替换为当前 app 后的版本与数据目录验证。

### 三平台可追踪矩阵

报告要求“关键桌面路径三平台矩阵可追踪”，本项目当前只有 Windows/macOS 桌面包，因此采用两层矩阵：

| 验证层 | Windows | macOS | Linux |
| --- | --- | --- | --- |
| Spring Boot + 浏览器核心流程 | 必须 | 必须 | 必须 |
| Tauri 安装/启动/升级 smoke | 必须 | 必须 | 不适用 |

Linux 不是新增分发目标，只用于证明 Java 本地服务和标准浏览器 UI 没有被 Windows/macOS 特有逻辑污染。

## 37-6：Bundle 与安装包体积门禁

### 前端 bundle

新增 `scripts/check-frontend-bundle-budget.mjs` 和基线文件 `cogniNote-agent-front/bundle-budget.json`。

基线从干净生产构建生成，至少记录：

- 入口 JS raw/gzip 大小。
- 最大 JS chunk raw/gzip 大小。
- 全部 JS gzip 总量。
- 全部 CSS gzip 总量。
- 生成时间、Node 版本和项目版本。

初始策略：

- 相对基线增长超过 2%：CI warning，并在 job summary 展示差异。
- 相对基线增长超过 5%：CI failure。
- 主入口 gzip 设置 490 KiB 初始硬上限；当前约 468 KiB，保留少量波动空间但不允许继续无边界增长。
- 最大 raw chunk 设置 1.55 MiB 初始硬上限；Phase 40 拆包后只允许下调，不允许上调来“修复”CI。
- 更新基线必须在 PR 中明确提交 `bundle-budget.json` 差异和原因。

阶段 37 不要求立刻消除现有大 chunk，只保证从此不再悄悄恶化。无效动态导入和按需加载在第 40 阶段处理。

### 桌面产物

新增 `artifacts/budgets/desktop-package-budget.json` 或等价仓库内基线，分别记录：

- Windows portable zip。
- Windows NSIS installer。
- macOS app zip。
- macOS DMG。
- 内嵌 backend 目录和主 jar。

签名、公证和压缩算法会造成小幅波动，因此规则为：

- 增长超过 5%：warning。
- 同时增长超过 10% 且绝对增加超过 20 MiB：failure。
- 依赖升级导致合理增长时必须更新基线，并在阶段/PR 文档中说明新增内容。
- 不允许通过排除必要 runtime、语言资源或签名文件来满足预算。

## Implementation Order

### 37-0：记录可复现基线

- 固定 JDK 25、Node 22、npm lockfile 和 Maven wrapper/命令约束。
- 保存一次后端、前端构建耗时和 bundle/安装包尺寸。
- 建立 `verify.yml` 的空骨架，但先不设 Required check。

### 37-1：后端隔离与资源关闭

- 替换所有固定 `target/test-cogninote-*` 配置。
- 修复锁、忙锁、Context 关闭和 executor 泄漏。
- 本地连续运行 5 次，再在 Windows CI 连续运行 20 次。

只有 20 次零失败后，才进入前端和发布门禁收口；否则后续 CI 失败会被不稳定后端测试淹没。

### 37-2：Vitest 首批测试

- 安装依赖和配置 test scripts。
- 先覆盖 utils/config，再覆盖 Chat、Maintenance、Model Config Store。
- 保持生产行为不变，只提取必要的纯函数测试 seam。

### 37-3：Playwright smoke

- 建立临时数据目录启动器和 fixture。
- 先打通首次启动、导入、搜索。
- 再增加 mock 模型 SSE、来源、维护和重启持久化。

### 37-4：CI 强制化

- PR/push 启用 `verify.yml`。
- 稳定一周后把 jobs 设为分支保护 Required checks。
- 将已有 knowledge health smoke 纳入 workflow。

### 37-5：发布依赖与产物 smoke

- Windows/macOS release workflow 添加 `needs: verify`。
- 增加 portable/app/backend 启动检查。
- 再增加安装、卸载和 N-1 升级检查。

### 37-6：体积门禁

- 先记录基线并只 warning。
- 两次正常构建确认波动范围后启用 failure threshold。
- 将阈值和更新规则写入贡献/发布文档。

## File Change Map

预计新增或修改：

```text
.github/workflows/verify.yml                         # PR/push/reusable 验证
.github/workflows/repeatability.yml                  # 定时与发布候选重复测试
.github/workflows/desktop-windows.yml                # 依赖 verify，增加 package smoke
.github/workflows/desktop-macos.yml                  # 依赖 verify，增加 package smoke

scripts/repeat-backend-tests.ps1                     # 连续执行与 JSON 报告
scripts/check-frontend-bundle-budget.mjs             # bundle 预算检查
scripts/smoke-desktop-package.ps1                    # Windows portable/installer smoke
scripts/smoke-desktop-package-macos.sh               # macOS app/DMG smoke
scripts/smoke-knowledge-health.ps1                    # 适配 CI 临时目录和报告输出

cogniNote-agent-front/package.json                   # test/test:e2e scripts 和依赖
cogniNote-agent-front/package-lock.json
cogniNote-agent-front/vitest.config.js
cogniNote-agent-front/playwright.config.js
cogniNote-agent-front/bundle-budget.json
cogniNote-agent-front/src/test/setup.js
cogniNote-agent-front/src/**/*.test.js
cogniNote-agent-front/e2e/**/*.spec.js
cogniNote-agent-front/e2e/fixtures/**
cogniNote-agent-front/src/domain/chat-normalizers.js  # 仅在需要测试 seam 时新增

src/test/java/**                                     # 固定目录改为 @TempDir
src/test/resources/**                                # 确定性 fixture/测试配置
docs/testing-and-release-gates.md                    # 本地与 CI 验证说明
```

具体测试文件按被测模块就近放置，不创建只有一层包装、没有行为价值的测试工具类。

## Acceptance Criteria

### 后端

- `rg "target/test-cogninote" src/test` 不再发现 Spring 集成测试固定存储路径。
- `mvn test` 单次通过，测试数不低于当前 208。
- Windows 上 `repeat-backend-tests.ps1 -Iterations 20` 连续零失败。
- 每轮结束后临时目录可删除，不存在 Lucene lock、SQLite busy 或残留 Java 进程。
- 失败轮次能在 artifact 中定位到具体 Surefire report。

### 前端

- `npm --prefix cogniNote-agent-front run test` 通过。
- 首批 utils/config/Store 测试覆盖正常、空值、非法值、失败恢复和重复事件。
- 测试不访问真实网络、真实 Tauri runtime 或用户 localStorage。
- `npm --prefix cogniNote-agent-front run build` 通过。
- bundle budget 检查通过，并在 CI summary 展示与基线差异。

### 浏览器 smoke

- 空数据目录可完成首次启动。
- fixture 可导入、搜索和打开来源。
- mock RAG SSE 可完成回答、来源展示和会话恢复。
- 维护任务能从开始走到完成并刷新健康状态。
- 相同临时数据目录重启后数据仍可读取。
- 流程不需要 API Key、外网、真实模型或人工点击。

### CI 与发布

- Pull Request 必须通过 backend、frontend、build、browser smoke 和 knowledge smoke。
- Windows/macOS 发布 job 明确依赖 reusable verify job。
- 不存在可以直接发布且无验证依赖的 workflow 路径。
- Windows portable/installer 和 macOS app/DMG 通过产物完整性检查。
- 当前版本至少完成一次 Windows/macOS 安装启动 smoke；发布候选完成 N-1 升级记录。
- 体积超阈值会使 workflow 失败，基线更新必须进入代码审查。

## Verification Commands

本地 Windows 主验证：

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
npm --prefix cogniNote-agent-front ci
npm --prefix cogniNote-agent-front run test
npm --prefix cogniNote-agent-front run build
node scripts/check-frontend-bundle-budget.mjs
npm --prefix cogniNote-agent-front run test:e2e
```

发布候选重复性验证：

```powershell
$ErrorActionPreference = 'Stop'
.\scripts\repeat-backend-tests.ps1 -Iterations 20
```

已有知识库 smoke：

```powershell
$ErrorActionPreference = 'Stop'
.\scripts\smoke-knowledge-health.ps1
```

## Risks And Mitigations

### Spring Context 数量增加

每个测试类使用不同动态路径会减少 Context cache 复用，测试时间可能增加。

应对：只对持久化集成测试隔离 Context；纯 controller/service 测试继续使用 mock。先保证正确和稳定，再通过测试切片优化时间。

### E2E 测试变成脆弱 UI 脚本

如果依赖中文按钮文本、动画时间和任意 sleep，页面微调会造成大量假失败。

应对：为关键控件增加稳定 `data-testid` 或可访问名称；等待 API 响应和明确状态，不使用固定长时间 sleep；每个 spec 只验证一个用户结果。

### Mock 过多导致“看起来通过”

如果所有 API 都由 Playwright mock，E2E 无法发现前后端契约问题。

应对：只有真实模型/外网相关 endpoint 使用 mock；导入、搜索、健康和维护走真实后端。后端模型工具链继续由 Java 集成测试覆盖。

### CI 时间和成本增加

后端、前端、E2E 和双平台打包全部串行会显著变慢。

应对：PR jobs 并行；打包只在发布工作流；20 次重复测试只在定时或发布候选执行；release packaging 复用成功的 verify 结果。

### 体积阈值误报

签名和压缩会导致产物大小有轻微变化。

应对：前端使用确定性 gzip 指标；桌面包同时使用相对和绝对阈值；先 warning 两次正常构建再启用 failure。

### Windows/macOS 安装测试污染环境

安装器可能写入默认 AppData、快捷方式或缓存。

应对：CI 使用临时用户目录和专用安装路径；finally/cleanup 始终执行；验证完成后检查残留进程和目录。绝不复用开发者真实配置。

## Rollback

- Vitest/Playwright 本身不改变生产 bundle；如 CI 环境不稳定，可暂时把单个 flaky spec 从 required smoke 移到 quarantine job，但必须登记 issue、失败证据和恢复期限，不能直接删除测试。
- bundle gate 可从 failure 暂时降为 warning，但基线数据和差异报告必须保留。
- 发布 workflow 回滚时仍必须保留至少 `mvn test`、前端 test/build 和产物 smoke，不允许恢复到无条件 `SkipTests`。
- 测试隔离修改如导致特定 Spring Context 无法启动，只回滚该测试类的动态配置实现，不恢复到共享固定目录；改用该类专属的唯一临时路径。

## Exit Decision

只有满足以下条件，项目才进入第 38 阶段“数据迁移与备份恢复”：

- 后端全量测试连续 20 次零失败。
- PR required checks 稳定运行至少一周，没有未处理的随机失败。
- 前端核心状态逻辑已有单元测试，关键用户路径已有确定性 Playwright smoke。
- Windows/macOS 发布 workflow 无法绕过验证，并能验证产物启动与版本。
- bundle 和桌面包体积基线已经记录，增长能被 CI 发现。

第 38 阶段会修改数据格式和恢复流程；如果第 37 阶段没有完成，上述高风险改动就没有可信的回归判断标准。
