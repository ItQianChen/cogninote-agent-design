package com.itqianchen.agentdesign.domain.support.ingestion;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrProgressException;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrPageProcessingException;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParseCheckpoint;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentParseRequest;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final int BLANK_PIXEL_CHANNEL_THRESHOLD = 245;
    private static final double MAX_BLANK_PAGE_NON_WHITE_RATIO = 0.00002d;

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
        return parse(DocumentParseRequest.direct(path));
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) {
        Path path = request.path();
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
                sections = parseWithOcr(path, document, request.checkpoint());
            }

            return new ParsedDocument(FileType.PDF, sections);
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to parse PDF file: " + path, ex);
        }
    }

    private List<ParsedSection> parseWithOcr(
            Path path,
            PDDocument document,
            DocumentParseCheckpoint checkpoint
    ) {
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
        int totalPages = document.getNumberOfPages();
        String parserSignature = "PDF_OCR:v1:dpi=" + OCR_RENDER_DPI + ":" + engine.checkpointSignature(settings);
        Map<Integer, ParsedSection> completedSections = prepareCheckpoint(
                checkpoint,
                parserSignature,
                totalPages
        );
        PDFRenderer renderer = new PDFRenderer(document);
        List<ParsedSection> sections = new ArrayList<>(completedSections.values().stream()
                .filter(section -> section.content() != null && !section.content().isBlank())
                .toList());
        int completedPages = completedSections.size();
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            int pageNumber = pageIndex + 1;
            if (completedSections.containsKey(pageNumber)) {
                continue;
            }
            try {
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, OCR_RENDER_DPI, ImageType.RGB);
                if (isVisuallyBlank(image)) {
                    checkpoint.save(new ParsedSection("", null, pageNumber));
                    completedPages++;
                    continue;
                }
                String text = engine.recognize(
                        new OcrPageImage(path, pageNumber, toPngBytes(image)),
                        settings
                );
                if (text == null || text.isBlank()) {
                    throw new OcrPageProcessingException(
                            DocumentFailureStage.MODEL_CALL,
                            DocumentFailureCode.MODEL_EMPTY_RESPONSE,
                            "视觉模型没有返回可用文字。",
                            "Vision model returned empty text for a non-blank PDF page",
                            pageNumber,
                            settings.provider().name(),
                            null
                    );
                }
                ParsedSection section = new ParsedSection(text, null, pageNumber);
                checkpoint.save(section);
                completedPages++;
                sections.add(section);
            } catch (IOException ex) {
                throw progressFailure(
                        checkpoint,
                        new OcrPageProcessingException(
                                DocumentFailureStage.OCR,
                                DocumentFailureCode.OCR_RENDER_FAILED,
                                "PDF 页面渲染失败。",
                                "Failed to render or encode PDF page " + pageNumber + ": " + path,
                                pageNumber,
                                settings.provider().name(),
                                ex
                        ),
                        completedPages,
                        totalPages,
                        pageNumber
                );
            } catch (RuntimeException ex) {
                throw progressFailure(checkpoint, ex, completedPages, totalPages, pageNumber);
            }
        }
        sections.sort((left, right) -> Integer.compare(left.pageNumber(), right.pageNumber()));
        if (sections.isEmpty()) {
            RuntimeException failure = new DocumentParseException("PDF OCR produced no usable text: " + path);
            throw progressFailure(checkpoint, failure, completedPages, totalPages, null);
        }
        return sections;
    }

    private static Map<Integer, ParsedSection> prepareCheckpoint(
            DocumentParseCheckpoint checkpoint,
            String parserSignature,
            int totalPages
    ) {
        try {
            Map<Integer, ParsedSection> sections = new LinkedHashMap<>();
            for (ParsedSection section : checkpoint.prepare(parserSignature, totalPages)) {
                if (section.pageNumber() != null
                        && section.pageNumber() >= 1
                        && section.pageNumber() <= totalPages) {
                    sections.put(section.pageNumber(), section);
                }
            }
            return sections;
        } catch (RuntimeException ex) {
            throw progressFailure(checkpoint, ex, 0, totalPages, 1);
        }
    }

    private static RuntimeException progressFailure(
            DocumentParseCheckpoint checkpoint,
            RuntimeException failure,
            int completedPages,
            int totalPages,
            Integer resumePage
    ) {
        return checkpoint.durable()
                ? new OcrProgressException(failure, completedPages, totalPages, resumePage)
                : failure;
    }

    private static byte[] toPngBytes(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }

    /**
     * 只把近乎纯白的页面视为确认空白，避免把模型偶发空响应误记为已完成页面。
     */
    private static boolean isVisuallyBlank(BufferedImage image) {
        long pixelCount = (long) image.getWidth() * image.getHeight();
        long maximumNonWhitePixels = Math.max(8L, (long) (pixelCount * MAX_BLANK_PAGE_NON_WHITE_RATIO));
        long nonWhitePixels = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                if (red < BLANK_PIXEL_CHANNEL_THRESHOLD
                        || green < BLANK_PIXEL_CHANNEL_THRESHOLD
                        || blue < BLANK_PIXEL_CHANNEL_THRESHOLD) {
                    nonWhitePixels++;
                    if (nonWhitePixels > maximumNonWhitePixels) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}


