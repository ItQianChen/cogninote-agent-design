# 第 36-3 阶段计划：PDF OCR 需求检测和用户可见诊断

## Summary

36-3 只把“PDF 没有可抽取文本层，需要 OCR”从普通解析失败中拆出来，形成可持久化、可展示、可健康诊断的问题状态。

本阶段不执行 OCR、不引入 Tesseract/Tika/新 Maven 依赖、不改 SQLite/Lucene schema、不实现 parser signature。

## Key Changes

- 新增 `PdfOcrRequiredException extends DocumentParseException`，作为无文本层 PDF 的稳定异常标记。
- `PdfDocumentParser` 继续使用 PDFBox `PDFTextStripper`；文本型 PDF 和部分页面有文本的 PDF 行为不变。
- 只有所有页面都无可用文本时抛出 `PdfOcrRequiredException("PDF has no extractable text layer; OCR is required: ...")`。
- 新增 `DocumentStatus.OCR_REQUIRED`，导入链路将该异常持久化为 `OCR_REQUIRED`；其他解析异常继续持久化为 `FAILED`。
- `OCR_REQUIRED` 文档仍计入 `failedCount`，chunk 数为 0，`indexedAt` 为 null，并清理旧 chunks 和 Lucene 索引。
- 新增 `KnowledgeHealthIssueCode.PDF_OCR_REQUIRED`；健康诊断将普通 `FAILED` 和 `OCR_REQUIRED` 拆成不同 issue。
- 前端把 `OCR_REQUIRED` 展示为“需 OCR”，并在健康问题中归到“资料未入库”。

## Boundaries

- 36-3 是诊断阶段，不提供 OCR 执行能力。
- 空白 PDF 和扫描 PDF 在无 OCR 情况下无法可靠区分，文案统一表述为“没有可抽取文本层，需要 OCR”。
- `failedCount` 保持“当前不能进入知识库检索的文档数”语义，因此包含 `FAILED` 和 `OCR_REQUIRED`。
- 真正的外部 Tesseract CLI OCR、OCR 配置和重新解析策略放到 36-4。

## Test Plan

- 文本型 PDF 仍按页解析。
- 空白/无文本层 PDF 抛出 `PdfOcrRequiredException`，消息包含 `OCR is required`。
- 混合 PDF 中有文本页和空白页时解析成功，只输出有文本的页。
- 损坏 PDF 仍是普通 `DocumentParseException`，不会误判为 OCR required。
- 导入无文本层 PDF 后 `failedCount=1`，失败消息包含 OCR required。
- 持久化文档 `fileType=PDF`、`status=OCR_REQUIRED`、`chunkCount=0`，搜索不可命中。
- 健康诊断能生成 `PDF_OCR_REQUIRED`，并与普通 `PARSE_FAILED` 共存时分别展示。

## Verification

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn '-Dtest=OfficeDocumentParserTests,DocumentIngestionServiceTests,DocumentIngestionFailureTests,KnowledgeHealthControllerTests' test
mvn test
npm --prefix cogniNote-agent-front run build
```
