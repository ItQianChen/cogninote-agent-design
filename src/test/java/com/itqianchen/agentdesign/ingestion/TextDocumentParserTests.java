package com.itqianchen.agentdesign.ingestion;


import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.support.ingestion.TextDocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 覆盖纯文本/Markdown 解析器的来源结构。
 *
 * <p>Markdown 标题会作为 ParsedSection.heading 进入后续 chunk 来源展示，不能只验证正文内容。</p>
 */
class TextDocumentParserTests {

    private final TextDocumentParser parser = new TextDocumentParser();

    @TempDir
    private Path tempDir;

    @Test
    void parseMarkdownReadsHeading() throws Exception {
        Path markdown = tempDir.resolve("note.md");
        Files.writeString(markdown, "# Title\n\nBody");

        ParsedDocument document = parser.parse(markdown);

        assertThat(document.fileType()).isEqualTo(FileType.MARKDOWN);
        assertThat(document.sections()).singleElement()
                .satisfies(section -> {
                    assertThat(section.heading()).isEqualTo("Title");
                    assertThat(section.content()).contains("Body");
                });
    }

    @Test
    void parseMarkdownAliasReadsHeading() throws Exception {
        Path markdown = tempDir.resolve("note.markdown");
        Files.writeString(markdown, "# Alias Title\n\nAlias body");

        ParsedDocument document = parser.parse(markdown);

        assertThat(document.fileType()).isEqualTo(FileType.MARKDOWN);
        assertThat(document.sections()).singleElement()
                .satisfies(section -> {
                    assertThat(section.heading()).isEqualTo("Alias Title");
                    assertThat(section.content()).contains("Alias body");
                });
    }
}


