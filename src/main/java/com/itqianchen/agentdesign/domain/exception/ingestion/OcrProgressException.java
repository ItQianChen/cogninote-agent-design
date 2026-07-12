package com.itqianchen.agentdesign.domain.exception.ingestion;

/**
 * 为 OCR 页面失败补充已持久化进度。
 *
 * <p>cause 保留原始错误分类；该异常只增加完成页数和下次续传页，不改变模型、OCR 或持久化错误码。</p>
 */
public class OcrProgressException extends DocumentParseException {

    private final RuntimeException processingFailure;
    private final int completedPages;
    private final int totalPages;
    private final Integer resumePage;

    public OcrProgressException(
            RuntimeException processingFailure,
            int completedPages,
            int totalPages,
            Integer resumePage
    ) {
        super(processingFailure.getMessage(), processingFailure);
        this.processingFailure = processingFailure;
        this.completedPages = completedPages;
        this.totalPages = totalPages;
        this.resumePage = resumePage;
    }

    public RuntimeException processingFailure() {
        return processingFailure;
    }

    public int completedPages() {
        return completedPages;
    }

    public int totalPages() {
        return totalPages;
    }

    public Integer resumePage() {
        return resumePage;
    }
}
