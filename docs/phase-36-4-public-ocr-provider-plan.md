# 第 36-4 阶段计划：视觉模型 OCR 与 PDF 重新解析

## Summary

36-4 从“公共 OCR Provider 接入”调整为“模型视觉识别 OCR”。无文本层 PDF 继续先由 PDFBox 判断，只有整篇无可抽取文本时，才把 PDF 页面渲染成图片，交给用户配置的多模态视觉模型识别文字。

本阶段不引入 Apache Tika，不实现 Tesseract CLI，不修改 Lucene schema，也不在 `documents` 增加 `parser_signature`。OCR 默认关闭，第一版只复用项目已有 `DASHSCOPE` 和 `OPENAI_COMPATIBLE` 模型 Provider，并新增独立 `VISION` 模型配置，不复用当前 Chat 模型。后续补充 SQLite 页级检查点表，用于长 PDF 的 OCR 断点续传。

## Key Changes

- 删除百度 OCR 专用能力：
  - 移除 `BaiduOcrEngine`、`BaiduOcrClient`、百度 token 获取、标准版/高精度版、`apiKey/secretKey`、`languageType`、`detectDirection` 等百度字段。
  - `OcrProvider.BAIDU_OCR` 仅作为旧配置反序列化哨兵保留；读取旧 `BAIDU_OCR` 设置时会归一化为 `MODEL_VISION` 且默认关闭。
  - 前端删除“百度 OCR”“标准版/高精度版”“百度密钥”等文案。
- 新增视觉模型配置：
  - `ModelConfigRole` 增加 `VISION`，`ModelCapability` 增加 `VISION`。
  - `model_configs` 表不新增列，`role` 文本枚举直接支持第三类 active 配置。
  - 默认视觉配置为 `DASHSCOPE + qwen3-vl-plus`，无 API Key，OCR 默认关闭。
  - 模型配置页新增“视觉识别模型”，密钥继续按本机配置逻辑明文可见、可复制、可清空。
- OCR 设置 API 保持路径不变：
  - `GET /api/ocr/settings`
  - `PUT /api/ocr/settings`
  - `POST /api/ocr/test`
  - 响应返回 `enabled`、`engine=MODEL_VISION`、当前视觉模型摘要、页数限制、超时和月调用预算提示。
  - 单页 OCR 超时范围为 `3` 到 `600` 秒，默认 `20` 秒；`600` 秒仅为允许上限。
  - 测试接口使用后端生成的小测试图片调用视觉模型，只验证图片输入和文本输出能力，不读取用户文件，不返回密钥。
- 后端识别流程：
  - `PdfDocumentParser` 仍然先抽 PDF 文本层。
  - 如果存在文本层，行为保持不变，不混合 OCR 空白页。
  - 如果所有页面都无可用文本：
    - OCR 未启用或 `VISION` 模型未配置 API Key：继续抛 `PdfOcrRequiredException`。
    - OCR 启用且可用：按页渲染 PNG，调用 `ModelVisionOcrEngine`，生成 `ParsedSection(text, null, pageNumber)`。
  - 识别 Prompt 统一配置在 `cogninote-prompts.yaml` 的 `app.ocr.prompts`，规则为只返回图片中的原文文字，尽量保留段落、换行、列表和表格结构，不解释、不总结、不翻译。
  - 经渲染像素检查确认近乎纯白的页面以空文本检查点标记为已完成；非空页面的模型空响应归类为 `MODEL_EMPTY_RESPONSE`，不保存检查点并允许普通同步重试。
  - 每页识别成功后立即写入 SQLite OCR 检查点；后续页面失败时保留已完成页，普通同步从首个未完成页面继续。
  - 文件哈希、Provider、Base URL、模型、Prompt、解析版本或渲染 DPI 变化时清除旧进度；超时、API Key 和预算变化不让检查点失效。
  - 整份 PDF 完成前不生成正式 chunks 或 Lucene 索引；完整落库后在同一事务清除检查点。
- 强制重新解析：
  - 不新增 `parser_signature` schema。
  - 沿用 `KnowledgeFolderRunOperation.REPARSE`。
  - `POST /api/knowledge-maintenance/runs/folders/{id}/reparse` 忽略 `isUnchanged(...)`，用于 OCR 配置开启后处理旧的 `OCR_REQUIRED` 文档。
  - 普通 `SYNC` 复用有效检查点；`REPARSE` 清除检查点并从第一页重新识别。
- 前端页面：
  - 设置中心保留“策略 / OCR 识别”，页面改为“模型 OCR”。
  - 页面展示当前视觉模型、启用状态、页数上限、超时、预算提示和测试连接。
  - 明确提示：启用后，无文本层 PDF 页面图片会上传到所选多模态模型服务商，可能产生模型 token 或图片费用。
  - `PDF_OCR_REQUIRED` 健康诊断在未配置时引导“配置视觉模型”；配置可用时引导“重新解析目录”；文档健康详情复用结构化失败对象展示已完成页数和续传页。

## Test Plan

- 后端：
  - `OcrSettingsServiceTests` 覆盖新默认值、旧 `BAIDU_OCR` 配置迁移、保存、关闭、预算/页数/超时归一化。
  - `ModelConfigServiceTests` 覆盖 `VISION` role 默认配置、激活、删除后兜底 active。
  - `ModelVisionOcrEngineTests` 使用 fake `AiChatRuntime` 覆盖图片调用、空结果、异常包装和 prompt 脱敏。
  - `PdfDocumentParserTests` 覆盖文本 PDF 不触发视觉模型、无文本层 PDF 未启用时仍为 `OCR_REQUIRED`、启用后按页生成 sections。
  - `PdfDocumentParserTests` 和检查点服务测试覆盖页面失败后续传、视觉空白页完成标记、非空页模型空响应重试、签名失效和重启恢复。
  - `DocumentIngestionServiceTests` 覆盖模型 OCR 成功后文档为 `PARSED`、chunk 数大于 0、搜索可命中。
- 前端：
  - OCR 设置页不再出现百度字段。
  - 视觉模型配置可新增、保存、激活、明文查看密钥。
  - OCR 测试成功/失败提示明确。
  - 目录“重新解析”入口和运行状态不回退。

验证命令：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn '-Dtest=OcrSettingsServiceTests,ModelVisionOcrEngineTests,PdfDocumentParserTests,DocumentIngestionServiceTests,ModelConfigServiceTests,ModelConfigControllerTests' test
mvn test
npm --prefix cogniNote-agent-front run build
```

## Assumptions

- 第一版不直连 Claude/Gemini 原生 API；需要这些模型时通过后续 Provider 扩展或 OpenAI-compatible 网关接入。
- 模型 OCR 结果不是传统 OCR 的逐字保证，可能受模型能力、图片清晰度、prompt 和费用限制影响。
- `monthlyCallBudget` 仍是本地提醒/配置，不做强制账单拦截。
- `OCR_REQUIRED`、`PDF_OCR_REQUIRED` 和 `REPARSE` 继续沿用；新增独立 SQLite OCR 检查点表，不修改正式 documents/chunks 字段或 Lucene schema。
- 旧百度 OCR 配置不会迁移密钥到视觉模型配置，避免误把百度密钥当模型 API Key 使用。

## References

- [OpenAI Images and Vision](https://developers.openai.com/api/docs/guides/images-vision)
- [Anthropic Vision](https://platform.claude.com/docs/en/build-with-claude/vision)
- [Gemini Image Understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
- [DashScope Qwen Vision](https://www.alibabacloud.com/help/en/model-studio/vision)
- [Spring AI Multimodality](https://docs.spring.io/spring-ai/reference/1.1/api/multimodality.html)
