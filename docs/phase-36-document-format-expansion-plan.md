# 第 36 阶段计划：文档格式扩展与 OCR 分层接入

## Summary

第 36 阶段扩展本地知识库导入能力，目标是在不重写现有摄入链路的前提下支持 HTML、老式 `.doc` 和图片型 PDF OCR。

当前摄入主线已经足够清晰：`FileType -> DocumentParserRegistry -> ParsedDocument/ParsedSection -> TextChunker -> SQLite/Lucene`。本阶段继续沿用这个边界，新增能力优先落在 `DocumentParser` 实现和少量文件类型识别上，不把导入、切块、索引和维护队列重新设计一遍。

本计划拆成 6 个子阶段：

1. 解析基础设施小改造。
2. HTML / HTM 导入。
3. Word 97-2003 `.doc` 导入。
4. PDF OCR 需求检测和用户可见诊断。
5. 外部 Tesseract CLI OCR 可选能力。
6. 内置 OCR 打包评估。

## Core Judgment

HTML 和 `.doc` 值得先做，风险低、收益直接。PDF OCR 必须分层做，不能和普通解析器一起上线。

- HTML 是本地知识库常见来源，`jsoup` 足够轻，能保持当前应用体积和解析边界可控。
- `.doc` 可继续使用 Apache POI 生态，只需增加 `poi-scratchpad` 和一个 parser。
- OCR 会引入 Tesseract、语言包、CPU/内存限制、临时文件、跨平台打包和重新解析策略，必须从检测开始，默认关闭。
- 暂不引入 Apache Tika 全量解析包。Tika 能统一抽取很多格式，但会增加体积和复杂性；官方也提醒生产处理不可信文件时需要隔离、超时和内存限制。

## Current Boundaries

现有接入点：

- `src/main/java/com/itqianchen/agentdesign/domain/enums/document/FileType.java`
- `src/main/java/com/itqianchen/agentdesign/domain/interfaces/ingestion/DocumentParser.java`
- `src/main/java/com/itqianchen/agentdesign/domain/support/ingestion/DocumentParserRegistry.java`
- `src/main/java/com/itqianchen/agentdesign/domain/support/ingestion/TextDocumentParser.java`
- `src/main/java/com/itqianchen/agentdesign/domain/support/ingestion/DocxDocumentParser.java`
- `src/main/java/com/itqianchen/agentdesign/domain/support/ingestion/PdfDocumentParser.java`
- `src/main/java/com/itqianchen/agentdesign/domain/support/ingestion/TextChunker.java`

原则：

- 新格式解析器只负责读取本地文件和抽取文本，不写 SQLite，不写 Lucene。
- 解析结果继续输出 `ParsedDocument` 和 `ParsedSection`。
- 切块继续由 `TextChunker` 统一处理，避免每个解析器产生自己的 chunk 规则。
- SQLite 仍是事实来源，Lucene 仍是可重建索引。
- 扫描、同步、失败记录和维护队列继续复用现有 `DocumentIngestionService`。

## Goals

- 支持本地 `.html` 和 `.htm` 文件导入。
- 支持 Word 97-2003 `.doc` 正文文本导入。
- PDF 无文本层时能明确诊断为“需要 OCR”，而不是普通解析失败。
- 提供可选外部 Tesseract CLI OCR，默认关闭。
- 保持未配置 OCR 的用户安装包体积和运行行为不变。
- 为后续 parser 版本、OCR 语言和 OCR DPI 变化后的重新解析预留机制。

## Non-goals

- 不在第一版引入 Apache Tika 全家桶。
- 不默认开启 OCR。
- 不把 OCR 结果写回用户原始 PDF。
- 不在本阶段实现图片文件导入，例如 `.png`、`.jpg`。
- 不承诺 OCR 版式还原，只把可搜索文本进入知识库。
- 不支持联网抓取网页。HTML 导入只处理本地文件。
- 不执行 Office 宏，不抽取嵌入对象。
- 不把 Tesseract 语言包直接塞进第一版安装包。

## 阶段 0：解析基础设施小改造

### 目标

先处理新增格式会共同碰到的文件类型和重解析问题，避免后面给每个 parser 打补丁。

### 改造点

- 将 `FileType` 从单扩展名改为多扩展名。
  - `MARKDOWN`: `.md`, `.markdown`
  - `TEXT`: `.txt`
  - `DOCX`: `.docx`
  - `DOC`: `.doc`
  - `PDF`: `.pdf`
  - `HTML`: `.html`, `.htm`
- 保持 `FileType.fromFileName(...)` 兼容旧行为，仍按文件名后缀识别。
- 保留 `FileType.extension()` 作为主扩展名兼容方法，新增 `extensions()` 返回完整不可变扩展名列表。
- 36-0 只保证新增扩展名进入扫描识别，不保证可完成导入；`.html`、`.htm`、`.doc` 在 parser 注册前实际导入会失败。
- 36-0 不能单独发布给用户，必须与阶段 1/2 合并发布，或在发布前临时增加“仅已注册 parser 文件可导入”的门控。
- 给解析器增加可选 parser signature 设计文档和测试约束。
  - 第一版可以先不改 schema。
  - OCR 阶段前必须决定是否增加 `parser_signature`，或提供强制重新解析入口。
  - 预留签名格式：

```text
TEXT:v1
MARKDOWN:v1
DOCX:poi-xwpf:v1
DOC:poi-hwpf:v1
PDF_TEXT:pdfbox:v1
HTML:jsoup:v1
PDF_OCR:tesseract-cli:v1:lang=chi_sim+eng:dpi=300
```

- 在健康诊断和维护动作中预留“文件未变但解析策略变了”的处理。

### 验收

- 已有 `.md`、`.txt`、`.docx`、`.pdf` 扫描行为不变。
- `.html`、`.htm`、`.doc` 会进入扫描列表。
- 36-0 测试只验证新增格式进入扫描，不执行 HTML/DOC 真实导入。
- 旧测试继续通过。

## 阶段 1：HTML / HTM 导入

### 实现状态

36-1 已落地，详见 [第 36-1 阶段计划：HTML / HTM 本地文档导入](phase-36-1-html-document-import-plan.md)。

当前实现新增 `org.jsoup:jsoup`，版本为 `${jsoup.version}` / `1.22.2`；只解析本地静态 HTML 文件，不执行 JavaScript，不联网抓取，不引入 Apache Tika。

### 实现方案

依赖选择：

```xml
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>${jsoup.version}</version>
</dependency>
```

新增：

- `HtmlDocumentParser`
- `HtmlDocumentParserTests`

解析规则：

- 使用 `Jsoup.parse(path, null, path.toUri().toString())` 读取本地 HTML。
- 删除 `script`、`style`、`noscript`、`svg`、`canvas`、`iframe`。
- 优先抽取 `main`、`article`、`body`，找不到时使用整个 document body。
- `title` 或第一个 `h1` 作为默认 heading。
- 按 `h1-h6` 拆分 `ParsedSection`，没有标题时输出单 section。
- `pre`、`code` 尽量转换为 Markdown fenced block，复用现有 `TextChunker` 的受保护块逻辑。
- 表格按行转成紧凑文本，不保留 HTML 标签。
- 链接保留可读文本，第一版不强制追加 URL，避免 chunk 噪音。

### 风险

- 真实 HTML 结构很脏，正文抽取可能带导航、页脚或菜单。
- `Element.text()` 会丢失代码块换行，所以 `pre/code` 需要单独处理。
- 如果直接使用 `body().text()`，技术文档的示例代码和表格会损失较多结构。

### 验收

- `.html` 和 `.htm` 都能被导入。
- `script/style` 内容不会进入 chunk。
- `h1` 或 `title` 进入 `ParsedSection.heading`。
- `pre/code` 中的换行和缩进尽量保留。
- 导入后搜索能命中 HTML 正文。

## 阶段 2：Word 97-2003 `.doc` 导入

### 实现状态

36-2 已落地，详见 [第 36-2 阶段计划：Word 97-2003 `.doc` 本地文档导入](phase-36-2-doc-document-import-plan.md)。

当前实现新增 `org.apache.poi:poi-scratchpad`，版本复用 `${poi.version}` / `5.5.1`；只解析 Word 97-2003 二进制 `.doc` 正文，不执行宏，不抽取嵌入对象，不引入 Apache Tika，不改 SQLite / Lucene schema。parser signature 设计值固定为 `DOC:poi-hwpf:v1`，但本阶段仍不新增 schema 字段。

### 实现方案

依赖选择：

```xml
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-scratchpad</artifactId>
    <version>${poi.version}</version>
</dependency>
```

新增：

- `DocDocumentParser`
- `OfficeDocumentParserTests` 中的 DOC 覆盖用例

解析规则：

- 使用 `org.apache.poi.hwpf.extractor.WordExtractor`。
- 第一版使用 `getText()` 输出单 section。
- 抽取结果为空时抛出 `DocumentParseException`。
- 对损坏文件、加密文件或不支持的旧格式保留清晰错误信息。

### 风险

- `.doc` 是 OLE2 二进制格式，历史文件差异大。
- Word 6/95 需要 `Word6Extractor`，第一版不承诺支持。
- 测试文件最好使用固定小 fixture，不要在单元测试里现场生成复杂 `.doc`。

### 验收

- `.doc` 会被扫描和导入。
- Word 97-2003 fixture 能抽出正文。
- 损坏 `.doc` 记录为失败文档，不中断批量导入。
- `.docx` 仍走 `DocxDocumentParser`，不会被 `.doc` parser 误处理。

## 阶段 3：PDF OCR 需求检测和用户可见诊断

### 实现方案

先不做 OCR，只把“需要 OCR”的状态从普通失败里分出来。

改造点：

- `PdfDocumentParser` 继续优先用 `PDFTextStripper` 抽文本层。
- 当所有页都无可用文本时，抛出明确错误：

```text
PDF has no extractable text layer; OCR is required: <path>
```

- 可选新增 `DocumentParseException` 子类型或错误 code，例如 `OCR_REQUIRED`。
- 健康诊断和导入失败详情将该类失败展示为“扫描件 PDF，需要 OCR”。

### 风险

- 部分页有文本层、部分页是图片。第一版应保留有文本层的页，不要因为部分空页直接失败。
- 空白 PDF 和扫描件 PDF 不能完全等价，错误文案要避免误导。

### 验收

- 文本型 PDF 解析行为不变。
- 全图片/空文本层 PDF 失败原因明确包含 OCR required。
- 失败记录能进入前端导入结果，用户知道下一步是开启 OCR 或使用外部工具。

## 阶段 4：外部 Tesseract CLI OCR 可选能力

### 实现方案

默认关闭，用户配置后才启用。第一版优先调用外部 `tesseract` 命令，不绑定 Tess4J，不打包语言包。

配置项：

```yaml
app:
  ingestion:
    ocr:
      enabled: false
      tesseract-path: ""
      languages: "chi_sim+eng"
      dpi: 300
      timeout-per-page-seconds: 30
      max-pages: 200
      max-image-megapixels: 40
```

新增接口：

```text
OcrEngine
  NoopOcrEngine
  TesseractCliOcrEngine
```

PDF OCR 流程：

```text
PDFTextStripper extracts text layer
  -> if enough text: return text sections
  -> if no text and OCR disabled: OCR_REQUIRED
  -> if no text and OCR enabled:
       PDFRenderer.renderImageWithDPI(pageIndex, dpi)
       write temp image
       ProcessBuilder(tesseract, image, stdout, -l, languages)
       collect page text
       delete temp image
       return ParsedSection(pageText, null, pageNumber)
```

实现约束：

- 使用 `ProcessBuilder` 参数数组，不能拼 shell 字符串。
- 临时文件必须写入应用临时目录或系统临时目录，并在 finally 中删除。
- 每页必须有超时。
- 超过 `max-pages` 或图片像素上限时拒绝 OCR，避免用户导入超大扫描件把机器跑满。
- OCR 失败不覆盖已有成功解析结果，继续遵守现有 `FailedDocumentPolicy`。
- OCR 结果只进入 SQLite chunks，不修改用户原 PDF。

### parser signature

OCR 阶段必须解决重新解析问题。至少选一个方案：

方案 A：增加 `parser_signature` 字段。

```text
PDF_TEXT:v1
PDF_OCR:tesseract-cli:v1:lang=chi_sim+eng:dpi=300
HTML:jsoup:v1
DOC:poi-hwpf:v1
```

当文件 hash 未变但 parser signature 变化时，重新解析。

方案 B：增加显式“强制重新解析”维护动作。

第一版可以先做 B，成本低；如果 OCR 设置会频繁变化，再补 A。

### 验收

- 未配置 OCR 时，扫描 PDF 仍只提示需要 OCR。
- 配置可用 Tesseract 后，扫描 PDF 能生成可搜索 chunk。
- 中英文语言配置能传给 Tesseract。
- 单页超时、命令不存在、语言包缺失时错误可读。
- OCR 临时文件不会残留。
- 大 PDF 不会无限占用 CPU 和内存。

## 阶段 5：内置 OCR 打包评估

### 目标

在外部 CLI 方案稳定后，再评估是否把 OCR 引擎和语言包打进安装包。

候选方案：

- 打包 Tesseract CLI。
- 使用 Tess4J / JNA 绑定。
- 引导用户安装系统 Tesseract。
- 支持 OCRmyPDF sidecar 文本作为高级外部工具。

评估维度：

- Windows/macOS 体积增量。
- 代码签名、公证和 native library 加载风险。
- `chi_sim`、`eng` 等语言包体积。
- 用户安装和错误排查成本。
- OCR 准确率、速度和稳定性。
- 是否影响第 30 阶段安装包减重目标。

结论规则：

- 如果内置 OCR 让安装包明显膨胀，默认继续使用外部 CLI。
- 如果未来用户强依赖 OCR，可考虑单独 OCR 增强包或插件化能力。
- 不把内置 OCR 和普通文档格式扩展混在同一次发布里。

## Data And Compatibility

- `documents.file_type` 已存枚举字符串，新增 `DOC`、`HTML` 后旧数据不受影响。
- 旧文档不会自动重新解析，除非用户执行同步、重建或强制重新解析。
- Lucene 索引格式不需要变化，新增格式仍写 chunk 文本。
- 前端文档列表如展示文件类型，需要兼容 `DOC` 和 `HTML`。
- API 文档和 README 需要同步支持格式说明。

## Testing Plan

后端测试：

- `FileType.fromFileName(...)` 覆盖 `.html`、`.htm`、`.doc`、大小写扩展名。
- `DocumentParserRegistry` 能找到 HTML、DOC、PDF parser。
- `HtmlDocumentParserTests` 覆盖标题、正文、代码块、脚本过滤。
- `OfficeDocumentParserTests` 覆盖正常 `.doc` fixture 和损坏文件。
- `PdfDocumentParserTests` 覆盖文本型 PDF、空文本层 PDF、部分页面为空。
- OCR 阶段增加 `TesseractCliOcrEngineTests`，用 fake executable 或 mock process runner，避免 CI 依赖真实 Tesseract。
- `DocumentIngestionServiceTests` 覆盖新增文件类型扫描、失败记录和跳过逻辑。

前端验证：

- 导入弹窗和文案展示新格式。
- 目录健康诊断能展示“需要 OCR”的失败原因。
- 文档列表能展示 `HTML`、`DOC`。

命令：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
npm --prefix cogniNote-agent-front run build
```

## Rollback

- HTML 和 `.doc` 回滚：移除新增 parser 和 `FileType` 枚举；已导入的 `HTML`/`DOC` 文档记录需要通过兼容清理脚本或维护动作删除，否则旧版本无法映射枚举。
- OCR 检测回滚：恢复 `PdfDocumentParser` 原错误文案即可，不影响数据。
- 外部 OCR 回滚：关闭 `app.ingestion.ocr.enabled`，保留普通 PDF 文本层解析。
- 内置 OCR 回滚：优先回滚打包资源和 native 加载，不改 SQLite schema。

## Acceptance Criteria

- HTML 和 `.doc` 能作为普通本地文档导入、切块、索引和 RAG 引用。
- 现有 Markdown、TXT、DOCX、文本型 PDF 行为不回退。
- 扫描件 PDF 在未开启 OCR 时给出明确“需要 OCR”提示。
- 开启外部 Tesseract OCR 后，扫描件 PDF 能进入知识库并可搜索。
- OCR 失败不会破坏已有解析成功的文档。
- 默认安装包不因为 OCR 明显膨胀。
- README、API 文档和阶段计划对支持格式、限制和安装要求描述一致。

## References

- [Apache POI Text Extraction](https://poi.apache.org/text-extraction.html)：`.doc` 使用 `poi-scratchpad` 中的 `WordExtractor`，`.docx` 使用 `XWPFWordExtractor`。
- [Apache POI HWPF Quick Guide](https://poi.apache.org/components/document/quick-guide.html)：旧 Word 文档文本抽取入口。
- [jsoup Extract attributes, text, and HTML](https://jsoup.org/cookbook/extracting-data/attributes-text-html)：HTML 文本抽取方法。
- [jsoup API](https://jsoup.org/apidocs/org/jsoup/Jsoup)：从文件、路径和字符串解析 HTML。
- [PDFBox PDFRenderer API](https://javadoc.io/static/org.apache.pdfbox/pdfbox/3.0.5/org/apache/pdfbox/rendering/PDFRenderer.html)：将 PDF 页面渲染成图片，供 OCR 使用。
- [Tess4J API](https://tess4j.sourceforge.net/docs/docs-5.19/net/sourceforge/tess4j/Tesseract1.html)：Java/JNA 绑定 Tesseract 的可选方案。
- [Tess4J GitHub](https://github.com/nguyenq/tess4j)：Tess4J 依赖 native library 和运行环境说明。
- [Tesseract traineddata docs](https://tesseract-ocr.github.io/tessdoc/Data-Files.html)：语言包 fast/best/tessdata 差异。
- [OCRmyPDF documentation](https://ocrmypdf.readthedocs.io/)：外部工具为扫描 PDF 增加 OCR 文本层或 sidecar 文本。
- [Apache Tika Java API](https://tika.apache.org/docs/4.0.0-SNAPSHOT/using-tika/java-api/index.html)：统一抽取方案和处理不可信文件的隔离提醒。

## Assumptions

- 项目继续以 JDK 25、Spring Boot、SQLite、Lucene 和 Tauri 桌面包作为主线。
- 第 36 阶段优先扩展用户本地资料覆盖面，不追求一次性支持所有文档格式。
- OCR 默认关闭，避免把少数高成本场景强加给所有用户。
- 如果后续引入插件机制，OCR 和 Tika 类重依赖可以迁移为可选能力。
