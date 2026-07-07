package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 覆盖导入入口共享的文件类型识别规则。
 *
 * <p>目录扫描、单文件导入和文本解析都会依赖这些后缀映射，新增格式时不能只改 parser。</p>
 */
class FileTypeTests {

    @Test
    void fromFileNameRecognizesAllSupportedExtensions() {
        assertThat(FileType.fromFileName("note.md")).contains(FileType.MARKDOWN);
        assertThat(FileType.fromFileName("note.markdown")).contains(FileType.MARKDOWN);
        assertThat(FileType.fromFileName("plain.txt")).contains(FileType.TEXT);
        assertThat(FileType.fromFileName("modern.docx")).contains(FileType.DOCX);
        assertThat(FileType.fromFileName("legacy.doc")).contains(FileType.DOC);
        assertThat(FileType.fromFileName("paper.pdf")).contains(FileType.PDF);
        assertThat(FileType.fromFileName("page.html")).contains(FileType.HTML);
        assertThat(FileType.fromFileName("archive.htm")).contains(FileType.HTML);
    }

    @Test
    void fromFileNameIgnoresExtensionCase() {
        assertThat(FileType.fromFileName("NOTE.MARKDOWN")).contains(FileType.MARKDOWN);
        assertThat(FileType.fromFileName("LEGACY.DOC")).contains(FileType.DOC);
        assertThat(FileType.fromFileName("PAGE.HTML")).contains(FileType.HTML);
    }

    @Test
    void fromFileNameReturnsEmptyForUnsupportedFile() {
        assertThat(FileType.fromFileName("image.png")).isEmpty();
    }

    @Test
    void extensionKeepsPrimaryExtensionAndExtensionsExposeAllAliases() {
        assertThat(FileType.MARKDOWN.extension()).isEqualTo(".md");
        assertThat(FileType.MARKDOWN.extensions()).containsExactly(".md", ".markdown");
        assertThat(FileType.HTML.extension()).isEqualTo(".html");
        assertThat(FileType.HTML.extensions()).containsExactly(".html", ".htm");
    }

    @Test
    void extensionsReturnsImmutableList() {
        List<String> extensions = FileType.HTML.extensions();

        assertThatThrownBy(() -> extensions.add(".xhtml"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
