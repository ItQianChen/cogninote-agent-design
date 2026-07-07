package com.itqianchen.agentdesign.domain.support.ingestion;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.service.ocr.OcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrEngineRegistry;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsProvider;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 解析带文本层的 PDF。
 *
 * <p>按页切成 ParsedSection，pageNumber 会透传到检索来源；只有整篇没有可抽取文本层时才走可选 OCR。</p>
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final int OCR_RENDER_DPI = 200;

    private final OcrSettingsProvider ocrSettingsProvider;
    private final OcrEngineRegistry ocrEngineRegistry;

    public PdfDocumentParser() {
        this(null, null);
    }

    @Autowired
    public PdfDocumentParser(OcrSettingsProvider ocrSettingsProvider, OcrEngineRegistry ocrEngineRegistry) {
        this.ocrSettingsProvider = ocrSettingsProvider;
        this.ocrEngineRegistry = ocrEngineRegistry;
    }

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
     * <p>pageNumber 会传递给检索来源展示；扫描件或纯图片 PDF 在 OCR 未启用时仍暴露 OCR_REQUIRED，
     * 防止导入一个无法搜索的空文档。</p>
     *
     * @param path PDF 文件路径
     * @return 按页组织的解析结果
     * @throws DocumentParseException 当文件不可读、PDF 损坏、OCR 未配置或 OCR 没有产出可用文本时抛出
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
                sections = parseWithOcr(path, document);
            }

            return new ParsedDocument(FileType.PDF, sections);
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to parse PDF file: " + path, ex);
        }
    }

    private List<ParsedSection> parseWithOcr(Path path, PDDocument document) {
        OcrSettingsSnapshot settings = ocrSettingsProvider == null
                ? OcrSettingsSnapshot.defaults()
                : ocrSettingsProvider.snapshot();
        if (!settings.available() || ocrEngineRegistry == null) {
            throw new PdfOcrRequiredException("PDF has no extractable text layer; OCR is required: " + path);
        }
        if (document.getNumberOfPages() > settings.maxPagesPerDocument()) {
            throw new DocumentParseException("PDF exceeds OCR page limit: " + path);
        }

        OcrEngine engine = ocrEngineRegistry.engineFor(settings.provider());
        PDFRenderer renderer = new PDFRenderer(document);
        List<ParsedSection> sections = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, OCR_RENDER_DPI, ImageType.RGB);
                String text = engine.recognize(
                        new OcrPageImage(path, pageIndex + 1, toPngBytes(image)),
                        settings
                );
                if (text != null && !text.isBlank()) {
                    sections.add(new ParsedSection(text, null, pageIndex + 1));
                }
            } catch (IOException ex) {
                throw new DocumentParseException("Failed to OCR PDF file: " + path, ex);
            }
        }
        if (sections.isEmpty()) {
            throw new DocumentParseException("PDF OCR produced no usable text: " + path);
        }
        return sections;
    }

    private static byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}


