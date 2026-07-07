# 第 36-0 阶段计划：解析基础设施小改造

## Summary

36-0 只做文档解析基础设施铺垫，不引入 `jsoup`、`poi-scratchpad`、OCR 或 Apache Tika，也不改 SQLite / Lucene 数据结构。

本阶段的核心目标是把 `FileType` 从“单扩展名枚举”改成“多扩展名文件类型”，并把后续 parser signature / 重新解析策略先固化为设计约束，避免 HTML、老 `.doc` 和 OCR 阶段各自补丁式扩展。

## Implementation Status

当前 36-0 已按本计划落地：

- `FileType` 已支持多扩展名。
- `.markdown`、`.doc`、`.html`、`.htm` 已进入文件类型识别和目录扫描。
- `extension()` 保持兼容，新增 `extensions()` 暴露全部扩展名。
- 未新增 HTML / DOC parser，未新增 Maven 依赖，未修改导入主链路。
- 测试只覆盖新增格式识别和扫描，不执行 HTML / DOC 真实导入。

## Key Changes

- 改造 `FileType`：
  - `MARKDOWN`: `.md`, `.markdown`
  - `TEXT`: `.txt`
  - `DOCX`: `.docx`
  - `DOC`: `.doc`
  - `PDF`: `.pdf`
  - `HTML`: `.html`, `.htm`
- 保持 `FileType.fromFileName(...)` 兼容旧行为，继续按文件名后缀识别，并保持大小写不敏感。
- 保留 `FileType.extension()`，返回主扩展名，避免破坏旧调用。
- 新增 `FileType.extensions()`，返回不可变扩展名列表。
- 保持现有解析链路不变：
  - 不修改 `DocumentParser` 接口。
  - 不注册 HTML / DOC parser。
  - 不新增 Maven 依赖。
  - 不修改 `DocumentIngestionService` 的导入、切块、持久化、索引主流程。

## Release Boundary

36-0 是内部基础阶段，不应单独作为用户版本发布。

原因是 `.html`、`.htm`、`.doc` 已经能被 `scanDocumentFiles(...)` 和目录扫描识别，但对应 parser 还没有注册。若用户实际导入这些文件，当前会在 `DocumentParserRegistry` 因 parser 未注册而进入失败记录。

发布到用户前必须满足二选一：

- 合并 36-1 HTML parser 和 36-2 DOC parser 后一起发布。
- 或临时增加“仅已注册 parser 的文件可导入”的门控，把扫描识别和可导入能力拆开。

## Parser Signature Design

36-0 不增加 `parser_signature` 字段，也不改变现有跳过未变化文件的判断逻辑。

后续 OCR 阶段前必须在以下方案中选择一个：

- 增加 `parser_signature` 持久化字段，当文件 hash 未变但解析策略变化时重新解析。
- 增加显式“强制重新解析”维护动作，作为第一版低成本方案。

预留签名格式：

```text
TEXT:v1
MARKDOWN:v1
DOCX:poi-xwpf:v1
DOC:poi-hwpf:v1
PDF_TEXT:pdfbox:v1
HTML:jsoup:v1
PDF_OCR:tesseract-cli:v1:lang=chi_sim+eng:dpi=300
```

## Test Plan

- `FileTypeTests`
  - 覆盖 `.md`、`.markdown`、`.txt`、`.docx`、`.doc`、`.pdf`、`.html`、`.htm`。
  - 覆盖大写扩展名。
  - 覆盖未知扩展名返回 empty。
  - 覆盖 `extension()` 返回主扩展名，`extensions()` 返回完整不可变列表。
- `TextDocumentParserTests`
  - 覆盖 `.markdown` 解析为 `MARKDOWN`，并保留 Markdown heading 行为。
- `DocumentIngestionServiceTests`
  - 覆盖 `.html`、`.htm`、`.doc` 进入扫描结果。
  - 只验证扫描，不调用 HTML / DOC 真实导入。

验证命令：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn '-Dtest=FileTypeTests,TextDocumentParserTests,DocumentIngestionServiceTests' test
mvn test
```

## Acceptance Criteria

- 已有 `.md`、`.txt`、`.docx`、`.pdf` 扫描行为不变。
- `.markdown` 识别为 `MARKDOWN`，并可走现有文本解析器。
- `.html`、`.htm`、`.doc` 会进入扫描列表。
- `FileType.extension()` 兼容旧调用。
- `FileType.extensions()` 返回不可变列表。
- 不新增 HTML / DOC / OCR 依赖。
- 不修改 SQLite / Lucene schema。
- 旧测试继续通过。

## Assumptions

- `documents.file_type` 当前存枚举名，新增 `DOC`、`HTML` 不影响旧数据。
- 前端展示 `DOC` / `HTML` 放到后续 parser 阶段一起处理。
- 本阶段不引入 Apache Tika，避免增加桌面包体积和不可信文件解析隔离成本。
- 36-1 和 36-2 会继续沿用 `FileType -> DocumentParserRegistry -> ParsedDocument/ParsedSection -> TextChunker -> SQLite/Lucene` 主链路。
