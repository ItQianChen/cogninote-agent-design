package com.itqianchen.agentdesign.domain.entity.document;

/**
 * PDF 模型 OCR 的文档级检查点。
 *
 * <p>sourceContentHash 和 ocrSignature 共同约束页面结果的可复用范围；任一变化都必须清空旧页面，
 * 避免把不同文件版本、模型或 Prompt 的识别结果混入同一文档。</p>
 */
public record DocumentOcrCheckpoint(
        String documentId,
        String sourceContentHash,
        String ocrSignature,
        int totalPages,
        long createdAt,
        long updatedAt
) {
}
