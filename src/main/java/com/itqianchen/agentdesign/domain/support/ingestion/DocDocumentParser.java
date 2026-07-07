package com.itqianchen.agentdesign.domain.support.ingestion;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 解析 Word 97-2003 二进制 DOC 文档文本。
 *
 * <p>该解析器只抽取正文文本，不执行宏，不还原批注、修订、图片、OLE 对象或复杂版式。</p>
 */
@Component
public class DocDocumentParser implements DocumentParser {

    /**
     * 仅接受旧版二进制 DOC，避免和 OOXML DOCX 解析器互相抢占。
     *
     * @param fileType 已识别的文件类型
     * @return 是否由当前解析器处理
     */
    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.DOC;
    }

    /**
     * 抽取 DOC 正文文本。
     *
     * <p>HWPF 只覆盖 Word 97-2003 二进制格式；加密文件、损坏文件、旧 Word 6/95 和
     * 伪装成 .doc 的 OOXML 文件都会作为解析失败返回给导入流程。</p>
     *
     * @param path DOC 文件路径
     * @return 单章节的解析结果
     * @throws DocumentParseException 当文件无法读取、格式不受支持或没有可用文本时抛出
     */
    @Override
    public ParsedDocument parse(Path path) {
        try (InputStream inputStream = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            if (!StringUtils.hasText(text)) {
                throw new DocumentParseException("DOC contains no usable text: " + path);
            }
            return new ParsedDocument(FileType.DOC, List.of(new ParsedSection(text, null, null)));
        } catch (DocumentParseException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new DocumentParseException("Failed to parse DOC file: " + path, ex);
        }
    }
}
