package com.itqianchen.agentdesign.domain.support.ingestion;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析本地 HTML/HTM 文件为可检索文本。
 *
 * <p>该解析器只处理磁盘上的静态 HTML，不执行脚本、不抓取外部资源；复杂结构会转换为
 * TextChunker 已能理解的 Markdown-ish 文本。</p>
 */
@Component
public class HtmlDocumentParser implements DocumentParser {

    private static final String REMOVED_ELEMENTS = "script, style, noscript, svg, canvas, iframe";

    /**
     * 仅处理本地 HTML/HTM 文件。
     *
     * @param fileType 已识别的文件类型
     * @return 是否由当前解析器处理
     */
    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.HTML;
    }

    /**
     * 抽取 HTML 正文并按标题拆分章节。
     *
     * @param path HTML 或 HTM 文件路径
     * @return 供切块流程消费的 HTML 文本结构
     * @throws DocumentParseException 当文件不可读或没有可用正文时抛出
     */
    @Override
    public ParsedDocument parse(Path path) {
        try {
            Document document = Jsoup.parse(path, null, path.toUri().toString());
            document.select(REMOVED_ELEMENTS).remove();

            HtmlSectionCollector collector = new HtmlSectionCollector(document.title());
            appendElement(contentRoot(document), collector);
            List<ParsedSection> sections = collector.sections();
            if (sections.isEmpty()) {
                throw new DocumentParseException("HTML contains no usable text: " + path);
            }
            return new ParsedDocument(FileType.HTML, sections);
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to parse HTML file: " + path, ex);
        }
    }

    private Element contentRoot(Document document) {
        Element main = document.selectFirst("main");
        if (main != null) {
            return main;
        }
        Element article = document.selectFirst("article");
        if (article != null) {
            return article;
        }
        Element body = document.body();
        return body == null ? document : body;
    }

    private void appendElement(Element element, HtmlSectionCollector collector) {
        String tagName = element.normalName();
        if (isHeading(tagName)) {
            collector.startSection(element.text(), headingLevel(tagName));
            return;
        }

        switch (tagName) {
            case "pre" -> collector.appendBlock(fencedCodeBlock(element));
            case "table" -> collector.appendBlock(tableText(element));
            case "p", "li", "blockquote" -> collector.appendBlock(element.text());
            default -> appendContainer(element, collector);
        }
    }

    private void appendContainer(Element element, HtmlSectionCollector collector) {
        String ownText = element.ownText();
        if (StringUtils.hasText(ownText)) {
            collector.appendBlock(ownText);
        }
        for (Element child : element.children()) {
            appendElement(child, collector);
        }
    }

    private int headingLevel(String tagName) {
        return Integer.parseInt(tagName.substring(1));
    }

    private boolean isHeading(String tagName) {
        return switch (tagName) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> true;
            default -> false;
        };
    }

    private String fencedCodeBlock(Element pre) {
        String code = normalizePreText(pre.wholeText());
        if (!StringUtils.hasText(code)) {
            return "";
        }
        return "```\n" + code + "\n```";
    }

    private String normalizePreText(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.startsWith("\n")) {
            normalized = normalized.substring(1);
        }
        return normalized.stripTrailing();
    }

    private String tableText(Element table) {
        return table.select("tr").stream()
                .map(this::tableRowText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String tableRowText(Element row) {
        return row.select("th, td").stream()
                .map(Element::text)
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" | "));
    }

    private static class HtmlSectionCollector {
        private final String documentTitle;
        private final List<ParsedSection> sections = new ArrayList<>();
        private final StringBuilder currentContent = new StringBuilder();
        private String currentHeading;

        private HtmlSectionCollector(String documentTitle) {
            this.documentTitle = StringUtils.hasText(documentTitle) ? documentTitle.trim() : null;
        }

        private void startSection(String heading, int headingLevel) {
            String normalizedHeading = normalizeText(heading);
            if (!StringUtils.hasText(normalizedHeading)) {
                return;
            }
            flush();
            currentHeading = normalizedHeading;
            currentContent.append("#".repeat(Math.max(1, Math.min(headingLevel, 6))))
                    .append(' ')
                    .append(normalizedHeading);
        }

        private void appendBlock(String block) {
            String normalizedBlock = normalizeBlock(block);
            if (!StringUtils.hasText(normalizedBlock)) {
                return;
            }
            ensureSection(normalizedBlock);
            if (!currentContent.isEmpty()) {
                currentContent.append("\n\n");
            }
            currentContent.append(normalizedBlock);
        }

        private List<ParsedSection> sections() {
            flush();
            return List.copyOf(sections);
        }

        private void ensureSection(String firstBlock) {
            if (currentHeading != null || !currentContent.isEmpty()) {
                return;
            }
            currentHeading = StringUtils.hasText(documentTitle) ? documentTitle : firstBlock;
            currentContent.append("# ").append(currentHeading);
        }

        private void flush() {
            String content = currentContent.toString().trim();
            if (StringUtils.hasText(content)) {
                sections.add(new ParsedSection(content, currentHeading, null));
            }
            currentContent.setLength(0);
            currentHeading = null;
        }

        private static String normalizeText(String text) {
            return text == null ? "" : text.replaceAll("\\s+", " ").trim();
        }

        private static String normalizeBlock(String block) {
            if (block == null) {
                return "";
            }
            if (block.startsWith("```")) {
                return block.trim();
            }
            if (block.contains("\n")) {
                return block.lines()
                        .map(HtmlSectionCollector::normalizeText)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.joining("\n"));
            }
            return normalizeText(block);
        }
    }
}
