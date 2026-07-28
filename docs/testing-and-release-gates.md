# 测试与发布门禁

本文说明 CogniNote Phase 37 的本地验证、CI required checks、按需重复诊断、体积预算和桌面发布 smoke。测试不得读取真实用户目录、真实 API Key、外网额度或第三方账号。

## 固定工具链

- JDK 25
- Node.js 22
- npm lockfile：`cogniNote-agent-front/package-lock.json`
- Maven 入口：`mvn`，由 Maven Enforcer 拒绝非 JDK 25
- PR 浏览器：Playwright Chromium；本地 Windows 在 Chromium 未安装时可使用系统 Chrome

Windows 本地验证前先确保 JDK 25 生效：

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

## 本地验证

最小提交前检查：

```powershell
$ErrorActionPreference = 'Stop'
mvn test
npm --prefix cogniNote-agent-front ci
npm --prefix cogniNote-agent-front run test
npm --prefix cogniNote-agent-front run build
node scripts/check-frontend-bundle-budget.mjs
```

关键路径检查：

```powershell
$ErrorActionPreference = 'Stop'
npm --prefix cogniNote-agent-front exec -- playwright install chromium
npm --prefix cogniNote-agent-front run test:e2e
.\scripts\smoke-knowledge-health.ps1
```

`test:e2e` 会构建后端 JAR，选择随机前后端端口，创建 `cogninote-e2e-*` 临时存储，结束时停止 Java 进程并清理存储。失败证据位于 `artifacts/browser-smoke/`。

## 后端隔离与按需重复诊断

持久化 Spring 集成测试使用类级 `@TempDir` 和 `TestStorageProperties` 注册独立 SQLite/Lucene 路径。由于 Context 持有独占数据源，这些测试使用 `@DirtiesContext(AFTER_CLASS)`，确保 JUnit 删除临时目录前关闭 Hikari 连接。`LuceneKnowledgeStore` 的 writer 和 `FSDirectory` 在同一 try-with-resources 中关闭，连续全量测试同时验证 Windows 临时目录可以在每轮结束后正常清理。

怀疑存在偶发锁、资源泄漏或测试污染时，可以在本地连续执行：

```powershell
$ErrorActionPreference = 'Stop'
.\scripts\repeat-backend-tests.ps1 -Iterations 5
```

脚本失败即停。每轮必须存在 Surefire XML 且至少执行一个非 skipped 测试，否则即使 Maven 返回 0 也会判定失败。机器可读摘要写入 `artifacts/test-repeatability/backend-repeatability.json`，每轮记录 `reportCount/tests/executedTests`，Surefire XML 保存在对应时间戳目录。重复次数按当前诊断风险决定；该脚本不由 CI 定时运行，也不是固定发布门禁。

2026-07-27 本地记录：全量 234 tests 单次通过，随后连续 5 轮均为 234 tests、0 failures、0 errors。

## 前端测试边界

Vitest 使用独立 `vitest.config.js` 和 happy-dom，不加载 Vite DevTools。`src/test/setup.js` 每个用例重建 Pinia、清空 DOM/localStorage 并恢复 mock。

首批用例保护：

- 健康 issue、文档失败和导航参数归一化。
- Chat session/message/source/reference/context 归一化和 SSE source 去重。
- 维护任务 snapshot refresh 合并、终态刷新和主动取消。
- 模型与联网设置的草稿保护、保存/测试失败后的 loading 恢复。

API 模块在 Store 测试中 mock，不发真实 HTTP 请求。当前不设全局覆盖率硬阈值；`npm run test:coverage` 用于发现空白，不允许为了百分比增加无行为价值 snapshot。

## Playwright 边界

真实本地后端覆盖首次启动、模型配置持久化、Markdown/TXT/HTML fixture 导入、关键词搜索、维护同步和同目录后端重启。以下两类 endpoint 使用 route mock：

- 模型连接测试：不触达真实 provider。
- Chat SSE：返回固定 `meta/delta/tool/done` 序列，验证来源去重、展示和会话重新加载。

Fixture 位于 `cogniNote-agent-front/e2e/fixtures/knowledge-base/`。浏览器测试不使用真实模型、API Key、Tauri runtime、用户 localStorage 或外网。

当前 5 个 spec 串行共享本轮隔离后端和存储，因此禁用单 spec retry。失败后应重跑完整 browser smoke；只有改成每个 test 独立后端和存储后，才能启用 Playwright retry。

## CI required checks

`.github/workflows/verify.yml` 支持 `pull_request`、master push、手动运行和 `workflow_call`：

| Check | 内容 |
| --- | --- |
| `Backend tests` | JDK 25，`mvn -B test`，失败时上传 Surefire |
| `Frontend tests` | Node 22，`npm ci`，Vitest |
| `Frontend build and budget` | 生产构建与 bundle budget |
| `Browser smoke` | Playwright Chromium 与隔离 Spring Boot |
| `Knowledge smoke` | 导入、搜索、健康、删除和运行记录清理专项 |

仓库分支保护必须把这五个 check 设为 Required。代码只能提供 workflow；GitHub 仓库设置需要维护者在 required checks 稳定运行一周后启用。

仓库不配置定时重复测试 workflow。普通提交和发布使用 `verify.yml` 的单次完整验证；需要排查偶发失败时，由开发者在本地运行重复脚本并保留报告。

## 发布依赖与桌面 smoke

`desktop-windows.yml` 和 `desktop-macos.yml` 都先调用 reusable `verify.yml`，打包 job 通过 `needs: verify` 依赖它。打包脚本中的 skip 参数只在该 job 出现，日志会明确说明测试已由上游验证完成。本地直接运行构建脚本时默认执行测试。

平台 smoke 检查：

- 安装包、app/portable 和嵌入 backend 存在。
- backend 在临时用户目录和随机端口启动，status 版本与 workflow 版本一致。
- 静态首页和一个 `/assets/**` 资源可读取。
- 包内没有 `.env`、测试数据库、日志或 E2E fixture。
- Windows 执行 NSIS 静默安装/卸载；macOS 挂载 DMG、复制到临时 Applications，再启动嵌入 backend。
- 签名模式继续执行原有 Authenticode、codesign、notary、stapler 和 Gatekeeper 校验。

N-1 覆盖升级属于 release candidate 验收，需要可用的上一版安装包，不能由普通 PR workflow 伪装。

## 体积预算

前端基线位于 `cogniNote-agent-front/bundle-budget.json`。相对增长超过 2% warning、超过 5% failure；入口 gzip 硬上限为 490 KiB，最大 raw chunk 硬上限为 1.55 MiB。

只有确认依赖或功能变化合理时才更新：

```powershell
$ErrorActionPreference = 'Stop'
npm --prefix cogniNote-agent-front run build
node scripts/check-frontend-bundle-budget.mjs --write-baseline
node scripts/check-frontend-bundle-budget.mjs
```

桌面基线位于 `artifacts/budgets/desktop-package-budget.json`。增长超过 5% warning；同时超过 10% 且绝对增加超过 20 MiB 时 failure。Windows installer、backend 和 JAR 已有历史基线；Windows portable 与 macOS app/DMG 必须在两次正常 release run 后提交评审值，缺失期间检查器明确输出 `BASELINE_REQUIRED`，不得填入估算值。

## 故障定位

- 后端随机失败：运行本地重复脚本，查看对应 iteration 的 Surefire XML，确认临时目录和 Hikari shutdown 日志。
- 浏览器失败：查看 `artifacts/browser-smoke/test-results/` 的 trace、截图、视频和 backend logs。
- bundle 失败：检查 job summary 的 baseline/current/change，不要直接提高硬上限。
- 桌面 smoke 失败：先区分包结构、backend status、静态资源、版本、安装器和签名阶段。
