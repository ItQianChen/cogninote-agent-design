# 第 36-2 阶段计划：Word 97-2003 `.doc` 本地文档导入

## Summary

36-2 补齐本地 `.doc` 的真实解析能力，让 36-0 已进入扫描列表的 `FileType.DOC` 可以完成导入、切块、索引和搜索命中。

执行时新建 `docs/phase-36-2-doc-document-import-plan.md`，内容采用本计划；代码层不改 `DocumentParser` 接口，不改 SQLite / Lucene schema，不引入 Apache Tika，不支持 Word 6/95、宏、嵌入对象或复杂版式还原。

## Key Changes

- Maven 新增 `org.apache.poi:poi-scratchpad`，版本复用现有 `${poi.version}` / `5.5.1`。
- 新增 `DocDocumentParser`：
  - `supports(FileType.DOC)` 返回 true，交给 Spring 自动注册进 `DocumentParserRegistry`。
  - 使用 POI HWPF / `WordExtractor` 抽取 Word 97-2003 二进制 `.doc` 正文。
  - 第一版输出单个 `ParsedSection`，`heading` 和 `pageNumber` 均为 `null`。
  - 抽取文本为空白时抛出 `DocumentParseException("DOC contains no usable text: ...")`。
  - 损坏文件、加密文件、OOXML 伪装 `.doc`、不支持旧格式等解析失败要包装为带路径上下文的 `DocumentParseException`。
- 保持现有导入链路不变：
  - `.doc` 继续由 `FileType.fromFileName(...)` 识别。
  - `DocumentIngestionService` 仍走 `DocumentParserRegistry -> ParsedDocument -> TextChunker -> SQLite/Lucene`。
  - `.docx` 仍由 `DocxDocumentParser` 处理，不能被 `.doc` parser 抢走。
- 文档同步：
  - 在第 36 阶段总计划中标记 36-2 落地状态、POI scratchpad 版本和边界。
  - README、项目设计文档和存在支持格式列表的 API 文档同步加入 `.doc`。
  - 标注 parser signature 设计值为 `DOC:poi-hwpf:v1`，但本阶段不新增 schema 字段。

## Test Plan

- 新增或扩展 Office parser 测试：
  - `DocDocumentParser` 支持 `FileType.DOC`，不支持 `DOCX`。
  - 固定小型 Word 97-2003 `.doc` fixture 能抽出正文。
  - 空正文或损坏 `.doc` 抛出 `DocumentParseException`。
  - `.docx` 仍由 `DocxDocumentParser` 正常解析。
- 补充集成测试：
  - `DocumentParserRegistry` 能找到 `FileType.DOC` parser。
  - `DocumentIngestionServiceTests` 导入 `.doc` 后 `parsedCount` 正确、文档状态为 `PARSED`、`fileType` 为 `DOC`、chunk 数大于 0。
  - 关键词搜索能命中 `.doc` 正文。
- 验证命令：
  ```powershell
  $env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
  $env:Path="$env:JAVA_HOME\bin;$env:Path"
  mvn '-Dtest=OfficeDocumentParserTests,DocumentIngestionServiceTests' test
  mvn test
  npm --prefix cogniNote-agent-front run build
  ```

## Assumptions

- 36-0 已完成：`FileType.DOC` 和 `.doc` 已存在并进入扫描。
- 36-1 已完成或同时发布：HTML/HTM parser 已注册，当前阶段只关闭 `.doc` 未注册 parser 的发布风险。
- 测试使用固定 `.doc` fixture，不在单元测试中动态生成旧二进制 Word。
- 本阶段只抽正文文本，不解析批注、修订、页码、目录、图片、OLE 嵌入对象或宏。
