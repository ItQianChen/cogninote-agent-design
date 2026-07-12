package com.itqianchen.agentdesign.domain.entity.document;

/**
 * 已完成的单页 OCR 原文。
 *
 * <p>pageText 允许为空字符串，用于标记确认过的空白页，防止后续同步反复调用模型。</p>
 */
public record DocumentOcrCheckpointPage(
        String documentId,
        int pageNumber,
        String pageText,
        long createdAt,
        long updatedAt
) {
}
