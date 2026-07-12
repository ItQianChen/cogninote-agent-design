package com.itqianchen.agentdesign.domain.dto.document;

/**
 * 旧版导入失败响应。
 *
 * @deprecated 新接口使用 {@link DocumentFailureResponse}；该类型仅保留源码兼容。
 */
@Deprecated
public record IngestFailureResponse(
        String sourcePath,
        String message
) {
    public DocumentFailureResponse toDocumentFailure() {
        return new DocumentFailureResponse(
                sourcePath,
                null,
                null,
                message,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        ).normalized();
    }
}


