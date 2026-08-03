# 持续数据库迁移契约

CogniNote 的数据库兼容边界由 schema 版本决定，不由应用版本号决定。应用启动时先读取 \`flyway_schema_history\`，再执行所有待执行的 Flyway migration；没有迁移历史的旧数据库必须先通过结构家族适配器，不能直接按某个应用版本强制 baseline。

## 永久规则

- 已发布的 \`V1__...sql\`、\`V2__...sql\`、\`V3__...sql\` 不得修改；结构变化只能追加 \`V4__...sql\`、\`V5__...sql\`。
- 历史适配器只识别结构家族并做明确的数据转换，不删除未知表、不猜测字段、不覆盖原始数据。
- 迁移开始前创建 SQLite 一致性快照，内部快照保留最近三份；迁移或校验失败时恢复快照。
- 应用版本仅用于诊断和备份 manifest；不得再出现“只支持 0.1.70”之类的启动判断。
- 未来 schema 不能由旧应用降级读取；检测到未来版本时应进入只读诊断/恢复流程。

## 发布门禁

包含 schema 变更的版本必须同时提交：

1. 新 Flyway migration。
2. 脱敏 fixture。
3. 从上一 schema 版本和最老支持 schema 的升级测试。
4. 迁移失败恢复原库的回滚测试。

纯应用版本发布不新增无意义的数据库 fixture，但仍需执行连续迁移和重复启动测试。

## 诊断接口

\`GET /api/system/status\` 和 \`GET /api/system/migration/status\` 返回 \`mode\`、\`databaseStatus\`、检测到的 schema 版本/家族、待执行 migration 和错误码。前端必须按这些字段展示迁移诊断，不得把失败归因成 JVM 损坏。
