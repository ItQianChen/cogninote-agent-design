# 第 38 阶段计划：数据迁移与备份恢复

第 38 阶段为本地 SQLite 数据建立正式版本历史、便携备份和失败可回滚的恢复流程。SQLite 继续作为唯一事实源；Lucene、日志、WebView 缓存和用户原始文件不进入备份。

## 核心判断

CogniNote 已经允许用户长期保存文档解析结果、聊天、模型配置、维护历史和知识图谱事实，但当前 schema 初始化只能处理空库或少量已知补列。继续发布新版本会产生两个真实风险：旧数据库无法可靠升级，以及数据库损坏或换机后没有正式恢复路径。

本阶段采用以下确定方案：

- 使用 Flyway SQL 顺序迁移。
- 设置页选择备份包、预检并确认，重启后恢复。
- 便携备份完整保留模型和联网搜索 API Key，并明确标记为敏感明文文件。
- 只支持当前 `0.1.70` schema 基线及以后正式版本，不兼容任意历史测试库。
- SQLite 是备份事实；Lucene 在恢复后重建。

## Schema 迁移

- 引入 Flyway；`flyway_schema_history` 是唯一 schema 版本源，不额外维护重复的版本表。
- `V1__baseline.sql` 固化 `0.1.70` 表和索引；`V2__data_protection_events.sql` 新增备份、恢复和迁移事件表。
- 新数据库依次执行 V1、V2；无 Flyway 历史的现有数据库必须通过表、列和索引识别，确认属于 `0.1.70` 后才 baseline 为 V1。
- 缺表、缺关键列、存在已知旧结构或 schema 高于应用支持版本时拒绝启动，不猜测迁移。
- 关闭自动 baseline、out-of-order 和 clean，启用迁移校验和。已发布 SQL 永不修改，后续变化只增加新版本文件。
- DDL 归 Flyway，默认模型配置保留为迁移完成后的幂等业务种子初始化。
- 在 Hikari 创建前处理 pending restore、执行迁移、运行 `quick_check` 和 `foreign_key_check`；全部成功后才开放连接池。
- 运行连接启用 `foreign_keys=ON`。旧库若存在引用错误，停止迁移并报告，不静默删除数据。
- 每次修改现有库前生成完整内部快照。迁移失败时关闭临时连接、恢复原数据库并验证，然后终止启动。内部快照保留最近三份。

## 备份格式

备份扩展名为 `.cogninote-backup`，ZIP v1 只允许：

```text
manifest.json
data/cogninote.db
```

`manifest.json` 固定包含：

```text
formatVersion, appVersion, schemaVersion, createdAt, platform
secretsPolicy = PLAINTEXT
contents[].path, sizeBytes, sha256
includes.sqlite = true
includes.lucene/originalFiles/logs/configFiles = false
settingsStoredInSqlite = true
```

- 使用 SQLite online backup 生成事务一致快照，不直接压缩正在使用的 `db`、`wal`、`shm` 文件。
- 模型和联网搜索 API Key 原样保留；导出确认框必须说明任何获得文件的人都可读取密钥。
- 备份先写入应用管理的临时目录，完成 SHA-256、SQLite 校验和 ZIP 封装后再原子发布。
- Tauri 通过保存对话框复制指定 `backupId` 对应的文件；REST API 不接受任意输出路径。
- 导出文件、内部快照和密钥不得进入日志、诊断包、测试 fixture 或 CI artifact。

## 恢复流程

1. Tauri 文件选择器把用户选择的包复制到受控 inbox，返回随机 `importId`；后端不读取前端提供的任意路径。
2. 预检拒绝重复路径、绝对路径、路径穿越、符号链接、未知文件和未来格式，并校验大小、SHA-256、schema、SQLite 和业务引用。
3. `manifest.json` 上限 256 KiB，数据库解压上限默认 20 GiB。可用空间必须覆盖待恢复数据库、当前数据库回滚副本和安全余量。
4. 预检返回数据量、来源版本、schema、创建时间、平台和明文密钥警告；一次只允许一个 pending restore。
5. 用户取消确认时立即丢弃明文工作副本并记录 `DISCARDED`；未确认副本超过 24 小时也自动清理。
6. 用户确认后写入受控 marker，Tauri 停止并等待后端退出，再调用 `app.restart()`。
7. 新进程在 Hikari 创建前保存当前数据库回滚副本，替换数据库，执行必要迁移和完整性校验。marker 同步记录 `SCHEDULED`、`SWAPPING`、`VALIDATING` 和 `REINDEXING`，每个阶段都可幂等续跑。
8. 任一步失败都恢复旧数据库并记录 `ROLLED_BACK`；如果回滚副本也无法验证，则停止启动。
9. 数据库成功后废弃旧 Lucene 目录并全量重建。重建失败不回滚已验证数据库，用户可从维护入口重试。

跨 Windows/macOS 恢复不改写原知识目录路径。路径不存在时由知识健康页报告，SQLite 中的 chunks、聊天和图谱仍可使用。

## API 与界面

新增接口：

```text
GET  /api/system/data-protection/status
POST /api/system/backups
POST /api/system/restores/preflight
POST /api/system/restores/{restoreId}/schedule
DELETE /api/system/restores/{restoreId}
GET  /api/system/restores/{restoreId}
```

- 备份响应包含 `backupId`、建议文件名、大小、SHA-256、schema 版本和 `containsSecrets=true`。
- 恢复状态统一为 `PREFLIGHTED`、`DISCARDED`、`SCHEDULED`、`SWAPPING`、`VALIDATING`、`REINDEXING`、`COMPLETED`、`ROLLED_BACK`、`REINDEX_FAILED`。
- 无效包返回 `422`，已有恢复任务返回 `409`，空间不足返回 `507`；错误响应不包含密钥或完整路径。
- Tauri 新增受控的备份保存、恢复导入和恢复后重启命令及最小权限声明。
- 设置中心“系统”分组新增“备份与恢复”，展示当前 schema、最近结果、备份入口和恢复入口。
- 恢复必须经过预检摘要和二次确认；重启后展示恢复与索引重建结果。
- 浏览器开发模式不开放本地文件恢复，只展示桌面版可用状态。

## 测试与验收

- 空库执行 V1/V2；`0.1.70` 无版本库正确 baseline；未知旧库和未来 schema 安全拒绝。
- 校验迁移 SQL 校验和、重复启动幂等、迁移失败还原原库以及内部快照保留策略。
- 在线写入期间生成备份，恢复后 SQLite 内容一致，API Key 完整保留。
- 覆盖损坏 ZIP、缺 manifest、哈希错误、ZIP slip、重复条目、超限解压、空间不足和引用损坏。
- 在 marker 各阶段模拟进程中断，验证重启后继续恢复或回滚，不出现半交换数据库。
- 验证恢复成功后 Lucene 从 SQLite 重建；重建失败时数据库仍可访问并可手动修复。
- 前端单元测试覆盖备份、预检、确认、重启、回滚和重建失败状态；Playwright 覆盖设置页主流程。
- Rust 测试覆盖受控目录解析、ID 校验、覆盖备份的失败回滚和重启前后端进程回收。
- Windows 和 macOS 安装包 smoke 使用临时应用目录完成“建数据、备份、修改、恢复、重启、检索”。
- 所有测试 fixture 使用假密钥，CI 产物不得包含真实用户数据库或备份包。

## 边界与发布

- 不实现密码加密、云备份、同步、用户原始文件打包、任意 `config/` 文件打包或路径自动重映射。
- 不承诺原地降级；需要降级时使用迁移前内部快照和对应旧版本。
- SHA-256 只用于完整性校验，不证明备份来源可信；恢复包始终按不可信输入处理。
- 正式发布前必须完成当前版本备份恢复、N-1 到当前迁移、Windows/macOS 重启恢复和失败回滚证据。
- 第 37 阶段的 20 轮后端测试、Required checks 稳定期、双平台安装 smoke 和桌面包体积基线仍是进入本阶段的外部门禁。

## 验证命令

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn test
npm --prefix cogniNote-agent-front run test
npm --prefix cogniNote-agent-front run build
node scripts/check-frontend-bundle-budget.mjs
cargo test --manifest-path cogniNote-agent-front/src-tauri/Cargo.toml
./scripts/smoke-backup-restore.ps1
```
