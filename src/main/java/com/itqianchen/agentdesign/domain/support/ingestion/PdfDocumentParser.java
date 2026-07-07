package com.itqianchen.agentdesign.domain.support.ingestion;


import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 解析带文本层的 PDF。
 *
 * <p>按页切成 ParsedSection，pageNumber 会透传到检索来源；当前阶段不做 OCR。</p>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    /**
     * 仅接受 PDF，注册表依赖该判断避免非 PDF 进入 PDFBox 解析流程。
     *
     * @param fileType 已识别的文件类型
     * @return 是否由当前解析器处理
     */
    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF;
    }

    /**
     * 按页抽取 PDF 文本层。
     *
     * <p>pageNumber 会传递给检索来源展示；扫描件或纯图片 PDF 当前不做 OCR，抽取为空时直接失败，
     * 防止导入一个无法搜索的空文档。</p>
     *
     * @param path PDF 文件路径
     * @return 按页组织的解析结果
     * @throws DocumentParseException 当文件不可读、PDF 损坏或没有可抽取文本层时抛出
     */
    @Override
    public ParsedDocument parse(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<ParsedSection> sections = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                // 逐页抽取才能在前置空白页存在时保留真实页码；整篇按分页符拆分会丢失空白页位置。
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isBlank()) {
                    sections.add(new ParsedSection(pageText, null, pageIndex + 1));
                }
            }

            if (sections.isEmpty()) {
                // 抽取结果为空通常意味着 PDF 没有文本层；当前阶段只诊断，不执行 OCR。
                throw new PdfOcrRequiredException("PDF has no extractable text layer; OCR is required: " + path);
            }

            return new ParsedDocument(FileType.PDF, sections);
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to parse PDF file: " + path, ex);
        }
    }
}


