# CogniNote Agent 第十六阶段计划：MyBatis 统一数据访问层

## Summary

第十六阶段把数据库访问统一迁移到原生 MyBatis + XML Mapper。目标不是承诺性能提升，而是为后续复杂 SQL、动态查询、统计聚合和业务扩展建立更清晰的 SQL 组织方式。

SQLite 仍是本地业务事实来源，底层继续通过 `sqlite-jdbc`、`DataSource` 和 Spring 事务体系访问；变化在业务代码层：Repository、schema 初始化和测试清库不再直接调用 `JdbcTemplate`，SQL 统一放到 Mapper XML。

本阶段不引入 MyBatis-Plus。当前表结构没有把单表 CRUD 标准化到足以吃满 MyBatis-Plus 的收益，反而会让 SQLite 方言、启动迁移、批量写入和复杂查询多一层不必要适配。

## Key Changes

- 引入 MyBatis：
  - 新增 `mybatis-spring-boot-starter`。
  - `application.yaml` 配置 `mapper-locations: classpath*:/mappers/*.xml`。
  - 使用 XML Mapper 作为唯一 SQL 组织方式，不使用注解 SQL。
  - 保留 `spring-boot-starter-jdbc`、`sqlite-jdbc`、`DataSource` 和 Spring 事务。
- 迁移业务数据访问：
  - 文档、chunk、知识库目录、模型配置、聊天会话和消息 SQL 迁移到 MyBatis Mapper XML。
  - Repository 层继续作为业务数据访问门面，Service 和 Controller 不直接依赖 Mapper。
  - Repository 对外方法签名保持稳定，避免上层大范围改动。
  - 原手写 `ResultSet` 映射改为 MyBatis `resultMap`，保留枚举、空值和时间戳语义。
- 统一 schema 初始化：
  - 建表、建索引、补列和旧 `model_config` 迁移通过 `DatabaseSchemaMapper` 执行。
  - `DatabaseSchemaInitializer` 保留启动编排职责，不再直接依赖 `JdbcTemplate`。
  - SQLite 方言 SQL 保留，包括 `ON CONFLICT`、`PRAGMA table_info`、`CREATE INDEX IF NOT EXISTS`。
- 清理 JdbcTemplate：
  - 生产代码移除 `JdbcTemplate` 注入和直接调用。
  - 测试清库改为 `TestDatabaseMapper` + `TestDatabaseCleaner`。
  - 不移除 JDBC 基础设施，因为 MyBatis、SQLite 驱动和事务仍依赖 JDBC。
- SQL 顺手治理：
  - 会话列表通过聚合查询返回消息数，避免按会话逐条 `countMessages`。
  - 大目录删除使用集合级 chunk 删除，避免循环逐文档清理 chunk。
  - 文档 chunk 写入继续在事务内保持分批能力，不退化为无事务散写。

## Test Plan

- 全量回归：
  - `mvn test`
  - 覆盖文档导入、失败导入、文档删除、目录导入、目录启用/停用、目录重建。
  - 覆盖模型配置 CRUD、active Chat/Embedding 切换、旧配置迁移兼容。
  - 覆盖会话创建、更新、删除、清空消息、历史消息恢复、RAG 对话、纯模型对话。
  - 覆盖 Lucene 全量重建和单目录重建。
- 数据兼容：
  - 旧版本 SQLite 启动后仍能补列。
  - 旧 `model_config` 仍能迁移到 `model_configs`。
  - 现有 documents/chunks/chat/model 数据读取语义不变。
- 架构约束：
  - `src/main/java` 和 `src/test/java` 不再直接出现 `JdbcTemplate`。
  - Mapper XML 保留 SQLite 方言 SQL。
  - Repository 不拼接复杂 SQL。
  - Service/Controller 不直接依赖 MyBatis Mapper。

## Assumptions

- MyBatis 用于架构统一和长期可维护性，不作为性能提升承诺。
- SQLite 仍是唯一业务数据库，不引入 MySQL/PostgreSQL 兼容目标。
- 本阶段不引入 Flyway/Liquibase，schema 初始化仍由应用启动流程负责。
- 不改变 REST API、前端行为、桌面打包链路和 SQLite/Lucene 分工。
