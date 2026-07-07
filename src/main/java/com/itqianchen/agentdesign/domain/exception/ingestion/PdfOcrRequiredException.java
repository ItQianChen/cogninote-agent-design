package com.itqianchen.agentdesign.domain.exception.ingestion;

/**
 * PDF 没有可抽取文本层，需要 OCR 后才能进入知识库。
 *
 * <p>该异常是导入链路区分“需要 OCR”和普通解析失败的稳定标记；当 OCR 未启用或配置不可用时抛出。</p>
 */
public class PdfOcrRequiredException extends DocumentParseException {

    /**
     * 使用可展示消息创建 OCR 需求异常。
     *
     * @param message 文档无法通过文本层解析的原因
     */
    public PdfOcrRequiredException(String message) {
        super(message);
    }
}
