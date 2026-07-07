package com.itqianchen.agentdesign.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.support.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.domain.support.ingestion.HtmlDocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 覆盖 HTML 解析器的结构抽取规则。
 *
 * <p>HTML 导入依赖 jsoup 把脏页面压成稳定文本；标题、代码块和表格结构会直接影响后续检索质量。</p>
 */
class HtmlDocumentParserTests {

    private final HtmlDocumentParser parser = new HtmlDocumentParser();

    @TempDir
    private Path tempDir;

    @Test
    void parseHtmlAndHtmAsHtmlDocument() throws Exception {
        Path html = writeHtml("page.html", "<main><p>HTML body</p></main>");
        Path htm = writeHtml("page.htm", "<main><p>HTM body</p></main>");

        assertThat(parser.parse(html).fileType()).isEqualTo(FileType.HTML);
        assertThat(parser.parse(htm).fileType()).isEqualTo(FileType.HTML);
    }

    @Test
    void registryFindsHtmlParser() {
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(parser));

        assertThat(registry.parserFor(FileType.HTML)).isSameAs(parser);
    }

    @Test
    void parseRemovesUnsafeAndNonTextElementsAndPrefersMain() throws Exception {
        Path html = writeHtml("clean.html", """
                <article><p>Article fallback should not be used.</p></article>
                <main>
                  <p>Main visible text.</p>
                  <script>secretScript()</script>
                  <style>.hidden { color: red; }</style>
                  <noscript>noscript text</noscript>
                  <svg><text>svg text</text></svg>
                  <canvas>canvas text</canvas>
                  <iframe>iframe text</iframe>
                </main>
                """);

        String plainText = parser.parse(html).plainText();

        assertThat(plainText).contains("Main visible text");
        assertThat(plainText)
                .doesNotContain("Article fallback")
                .doesNotContain("secretScript")
                .doesNotContain("hidden")
                .doesNotContain("noscript text")
                .doesNotContain("svg text")
                .doesNotContain("canvas text")
                .doesNotContain("iframe text");
    }

    @Test
    void parseFallsBackToArticleWhenMainIsMissing() throws Exception {
        Path html = writeHtml("article.html", """
                <body>
                  <article><p>Article content.</p></article>
                  <section><p>Body fallback should not be used.</p></section>
                </body>
                """);

        String plainText = parser.parse(html).plainText();

        assertThat(plainText).contains("Article content");
        assertThat(plainText).doesNotContain("Body fallback");
    }

    @Test
    void parseSplitsSectionsByHeadingsAndKeepsHeadingInContent() throws Exception {
        Path html = writeHtml("headings.html", """
                <main>
                  <h1>Guide</h1>
                  <p>Intro text.</p>
                  <h2>Install</h2>
                  <p>Install jsoup.</p>
                </main>
                """);

        ParsedDocument document = parser.parse(html);

        assertThat(document.sections())
                .hasSize(2)
                .satisfiesExactly(
                        section -> {
                            assertThat(section.heading()).isEqualTo("Guide");
                            assertThat(section.content()).startsWith("# Guide");
                            assertThat(section.content()).contains("Intro text.");
                        },
                        section -> {
                            assertThat(section.heading()).isEqualTo("Install");
                            assertThat(section.content()).startsWith("## Install");
                            assertThat(section.content()).contains("Install jsoup.");
                        }
                );
    }

    @Test
    void parseUsesTitleWhenDocumentHasNoHeading() throws Exception {
        Path html = writeHtml("title.html", """
                <head><title>Release Notes</title></head>
                <body><main><p>Version notes body.</p></main></body>
                """);

        ParsedDocument document = parser.parse(html);

        assertThat(document.sections()).singleElement()
                .satisfies(section -> {
                    assertThat(section.heading()).isEqualTo("Release Notes");
                    assertThat(section.content()).startsWith("# Release Notes");
                    assertThat(section.content()).contains("Version notes body.");
                });
    }

    @Test
    void parsePreservesPreCodeAsFencedBlock() throws Exception {
        Path html = writeHtml("code.html", """
                <main>
                  <h1>Code</h1>
                  <pre><code>function run() {
                    return true;
                }</code></pre>
                </main>
                """);

        String plainText = parser.parse(html).plainText();

        assertThat(plainText)
                .contains("```")
                .contains("function run() {")
                .contains("  return true;")
                .contains("}\n```");
    }

    @Test
    void parseTableRowsAsCompactText() throws Exception {
        Path html = writeHtml("table.html", """
                <main>
                  <table>
                    <tr><th>Name</th><th>Value</th></tr>
                    <tr><td>Mode</td><td>HTML</td></tr>
                  </table>
                </main>
                """);

        assertThat(parser.parse(html).plainText()).contains("Name | Value\nMode | HTML");
    }

    @Test
    void parseEmptyHtmlFails() throws Exception {
        Path html = writeHtml("empty.html", """
                <html>
                  <body><script>onlyScript()</script><style>body {}</style></body>
                </html>
                """);

        assertThatThrownBy(() -> parser.parse(html))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("HTML contains no usable text");
    }

    private Path writeHtml(String fileName, String body) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, "<!doctype html><html>" + body + "</html>");
        return path;
    }
}
