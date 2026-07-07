# 第 36-1 阶段计划：HTML / HTM 本地文档导入

## Summary

36-1 补齐本地 `.html` / `.htm` 的真实解析能力，让 36-0 已经进入扫描列表的 `FileType.HTML` 可以完成导入、切块、索引和搜索命中。

本阶段不改 `DocumentParser` 接口，不改 SQLite / Lucene schema，不引入 Apache Tika，不做网页抓取，只解析用户本机已有的静态 HTML 文件。

## Implementation Status

当前 36-1 按本计划落地：

- 新增 `org.jsoup:jsoup` 轻量依赖，版本为 `1.22.2`。
- 新增 `HtmlDocumentParser` 并通过 Spring 注入 `DocumentParserRegistry`。
- `.html` / `.htm` 可被目录导入解析为 `FileType.HTML` 文档。
- HTML 正文会转换为 `ParsedDocument` / `ParsedSection`，继续复用现有 `TextChunker`、SQLite 和 Lucene 流程。
- README 和项目方案中的支持格式说明已同步。

## Key Changes

- Maven 新增 `jsoup.version` 和 `org.jsoup:jsoup` 依赖。jsoup 不引入 Tika 全家桶，也不需要额外运行时依赖。
- 新增 `HtmlDocumentParser`：
  - `supports(FileType.HTML)` 返回 true。
  - 使用 `Jsoup.parse(path, null, path.toUri().toString())` 读取本地 HTML，并允许 jsoup 根据 BOM / meta 自动识别字符集。
  - 解析前删除 `script, style, noscript, svg, canvas, iframe`。
  - 正文根节点按 `main -> article -> body -> document` 优先级选择。
  - 不执行 JavaScript，不联网抓取，不抽取 iframe 或外链资源。
- HTML 转文本规则：
  - `h1-h6` 开启新 `ParsedSection`，`heading` 使用标题纯文本。
  - 每个 section content 以 Markdown heading 行开头，保证标题能进入 BM25、向量和预览。
  - 没有标题时输出单 section，heading 优先使用 `<title>`，否则使用第一个非空正文片段。
  - `pre` 使用 `wholeText()` 转为 fenced code block，保留换行和缩进。
  - 表格按行转为 `cell | cell` 紧凑文本，不保留 HTML 标签。
  - 链接只保留可读文本，第一版不追加 URL。
  - 抽取后正文为空时抛出 `DocumentParseException("HTML contains no usable text: ...")`。

## Test Plan

- `HtmlDocumentParserTests`
  - `.html` 和 `.htm` 都解析为 `FileType.HTML`。
  - `script/style/noscript/svg/canvas` 内容不会进入 `plainText()`。
  - `main` 优先于 `article/body`，缺失时可 fallback。
  - `h1-h6` 拆成多个 `ParsedSection`，heading 和 content 中的 Markdown heading 正确。
  - 无标题 HTML 使用 `<title>` 作为 section heading。
  - `pre/code` 保留换行、缩进和 fenced block。
  - 表格行按 `cell | cell` 输出。
  - 空正文 HTML 抛出 `DocumentParseException`。
- 集成测试：
  - `DocumentParserRegistry` 能找到 `FileType.HTML` parser。
  - `DocumentIngestionServiceTests` 导入 `.html` / `.htm` 后 `parsedCount` 正确、文档状态为 `PARSED`、chunk 数大于 0。
  - 关键词搜索能命中 HTML 正文或标题。

验证命令：

```powershell
$env:JAVA_HOME='D:\CodeApps\Java-JDK\jdk-25.0.2'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn '-Dtest=HtmlDocumentParserTests,DocumentIngestionServiceTests' test
mvn test
npm --prefix cogniNote-agent-front run build
```

## Acceptance Criteria

- `.html` 和 `.htm` 能作为普通本地文档导入。
- HTML 正文能进入 chunks，并能被本地搜索命中。
- `script/style/noscript/svg/canvas/iframe` 不进入知识库正文。
- `pre` 中的代码结构不会被普通文本清洗破坏。
- 表格内容以可读文本形式进入知识库。
- 现有 Markdown、TXT、DOCX、PDF 行为不回退。
- 不修改数据库 schema 和 Lucene 字段。

## Assumptions

- 36-0 已完成：`FileType.HTML`、`.html`、`.htm` 已存在并进入扫描。
- 前端当前直接展示 `document.fileType`，36-1 不需要新增专门 UI 映射。
- 本阶段不解决 parser signature / 强制重新解析，仍沿用当前文件 size、mtime、hash 的跳过逻辑。
- 36-2 会继续在同一解析链路上补 `.doc` parser。
