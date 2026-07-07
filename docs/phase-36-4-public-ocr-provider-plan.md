# 第 36-4 阶段计划：公共 OCR Provider 接入与 PDF 重新解析

## Summary

36-4 从“外部 Tesseract CLI OCR”调整为“公共 OCR Provider 接入”。第一版优先实现 `BAIDU_OCR`，用于把 36-3 标记为 `OCR_REQUIRED` 的无文本层 PDF 重新解析进知识库。

本阶段不引入 Apache Tika，不实现 Tesseract CLI，不新增 SQLite / Lucene schema，不实现 `parser_signature`。OCR 只处理 PDF 无文本层场景，已有文本层 PDF 继续走 PDFBox 文本抽取。

## Key Changes

- 后端新增 OCR Provider 抽象：
  - `OcrProvider` 枚举第一版只启用 `BAIDU_OCR`。
  - `OcrEngine` 接口负责把 PDF 页面图片识别成纯文本。
  - `BaiduOcrEngine` 调用百度通用文字识别接口，默认使用标准版，高精度版作为可选模式。
  - OCR 只处理 PDF 无文本层场景；已有文本层 PDF 不走 OCR。
- OCR 设置持久化：
  - 使用 `app_settings`，key 为 `ocr.settings`。
  - 默认配置：

```json
{
  "enabled": false,
  "provider": "BAIDU_OCR",
  "baidu": {
    "apiKey": "",
    "secretKey": "",
    "recognitionMode": "STANDARD",
    "languageType": "CHN_ENG",
    "detectDirection": true
  },
  "limits": {
    "maxPagesPerDocument": 200,
    "timeoutPerPageSeconds": 20,
    "monthlyCallBudget": 1000
  }
}
```

- OCR 设置 API：
  - `GET /api/ocr/settings` 返回本机保存的 `apiKey` / `secretKey` 明文，便于用户核对和修改。
  - `PUT /api/ocr/settings` 保存 OCR 设置；`apiKey` / `secretKey` 为 `null` 时沿用旧值，空字符串表示清空。
  - `POST /api/ocr/test` 只验证百度鉴权 token 与服务可用性，不主动识别用户文件。
  - 测试响应只返回 `success/message/provider/mode`，不得包含密钥或 access token。
  - 禁止在日志、异常消息、测试结果和维护运行记录中输出密钥。
- PDF OCR 流程：
  - `PdfDocumentParser` 继续优先使用 PDFBox `PDFTextStripper` 抽取文本层。
  - 如果存在文本层，行为保持不变，不混合 OCR 空白页。
  - 如果所有页面都无可用文本：
    - OCR 未启用或配置不可用：继续抛 `PdfOcrRequiredException`。
    - OCR 启用且可用：按页渲染图片，调用 OCR Provider，生成 `ParsedSection(pageText, null, pageNumber)`。
  - OCR 输出为空时抛普通 `DocumentParseException("PDF OCR produced no usable text: ...")`。
  - OCR 不修改用户原始 PDF，只写入 SQLite chunks 和 Lucene 索引。
- 强制重新解析：
  - 不新增 `parser_signature` schema。
  - 新增 `KnowledgeFolderRunOperation.REPARSE`。
  - 新增接口 `POST /api/knowledge-maintenance/runs/folders/{id}/reparse`。
  - `REPARSE` 忽略 `isUnchanged(...)`，强制重新解析目录内支持文件，用于 OCR 配置开启后处理旧的 `OCR_REQUIRED` 文档。
  - 普通 `SYNC` 仍保持增量跳过，`REBUILD_INDEX` 仍只负责索引重建。
- 前端页面：
  - 设置中心新增 `策略 / OCR 识别`。
  - 页面结构沿用联网搜索设置页：状态摘要、基础配置、识别策略、测试/保存按钮。
  - 密钥输入保存后继续明文回显，并提供“清空密钥”按钮。
  - 页面明确提示：启用公共 OCR 后，无文本层 PDF 页面图片会上传到百度 OCR，可能产生按页调用费用。
  - `PDF_OCR_REQUIRED` 健康诊断在 OCR 未配置时引导“配置 OCR”；OCR 可用时引导“重新解析目录”。
  - 目录管理页增加“重新解析目录”入口，并支持 `REPARSE` 运行状态展示。

## Test Plan

- 后端：
  - `OcrSettingsServiceTests` 覆盖默认值、保存、明文回显、清空密钥、参数归一化。
  - `BaiduOcrEngineTests` 使用 fake HTTP client 覆盖 token 获取、标准版/高精度版请求、超时、额度错误、空结果和密钥脱敏日志。
  - `PdfDocumentParserTests` 覆盖文本型 PDF 不触发 OCR、无文本层 PDF 未启用 OCR 时仍为 `OCR_REQUIRED`、启用 OCR 后生成 sections。
  - `DocumentIngestionServiceTests` 覆盖 OCR 成功后 `PARSED`、chunk 数大于 0、搜索可命中；OCR 失败时失败统计正确。
  - `KnowledgeMaintenanceControllerTests` 覆盖 `REPARSE` 入队和执行。
- 前端：
  - OCR 设置页保存后继续显示密钥明文。
  - 清空密钥后不能启用 OCR。
  - 测试连接成功/失败都有明确反馈。
  - `PDF_OCR_REQUIRED` 在未配置和已配置 OCR 两种状态下显示不同动作。
  - 目录“重新解析”入队后 busy、SSE、运行记录和刷新行为正确。
  - 375px、768px、1024px、1440px 下无横向滚动。

验证命令：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn '-Dtest=OcrSettingsServiceTests,BaiduOcrEngineTests,PdfDocumentParserTests,DocumentIngestionServiceTests,KnowledgeMaintenanceControllerTests,KnowledgeHealthControllerTests' test
mvn test
npm --prefix cogniNote-agent-front run build
```

## Assumptions

- 36-4 第一版只实现百度 OCR 公共云 Provider。
- 百度 OCR 费用、免费额度和接口限制以官方页面为准；前端只显示保守提示，不承诺账单准确性。
- 本阶段只 OCR PDF，不新增图片文件导入。
- OCR 默认关闭，用户必须显式启用。
- Tesseract CLI、本地离线 OCR、Azure/Tencent/OCR.space 等留作后续 Provider 扩展。

## References

- [百度 OCR 价格详情](https://cloud.baidu.com/product-price/ocr.html)
- [百度通用场景文字识别价格](https://ai.baidu.com/ai-doc/OCR/9k3h7xuv6)
- [百度 OCR 接口说明](https://cloud.baidu.com/doc/OCR/s/7kibizyfm)
