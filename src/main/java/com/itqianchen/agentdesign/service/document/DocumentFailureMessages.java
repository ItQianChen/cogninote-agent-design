package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;

/** 文档失败错误码对应的用户操作建议。 */
public final class DocumentFailureMessages {

    private DocumentFailureMessages() {
    }

    public static String suggestion(String codeValue) {
        return suggestion(parseCode(codeValue));
    }

    /**
     * 返回稳定错误码对应的统一用户建议。
     *
     * @param code 文档失败错误码
     * @return 用户可执行的修复建议
     */
    public static String suggestion(DocumentFailureCode code) {
        return switch (code) {
            case FOLDER_NOT_FOUND -> "检查目录是否存在，以及应用是否有读取权限。";
            case FOLDER_SCAN_FAILED -> "检查目录权限、占用状态和路径是否可访问后重新同步。";
            case FILE_METADATA_FAILED, FILE_READ_FAILED -> "检查文件是否存在、是否被占用以及当前用户是否有读取权限。";
            case DOCUMENT_CORRUPTED, DOCUMENT_ENCRYPTED -> "修复、解密或重新导出文件后再次同步。";
            case NO_USABLE_TEXT, CHUNK_EMPTY -> "确认文件包含可读取正文，必要时重新导出为受支持格式。";
            case OCR_REQUIRED -> "启用视觉模型 OCR 后重新解析目录，或先用外部工具写入 PDF 文本层。";
            case OCR_PAGE_LIMIT -> "提高单文档 OCR 页数上限，或拆分 PDF 后重新解析。";
            case OCR_RENDER_FAILED, OCR_EMPTY_RESULT -> "检查 PDF 是否损坏，并尝试重新导出后再次解析。";
            case MODEL_NOT_CONFIGURED -> "配置并启用视觉识别模型，确认 API Key 和模型名称有效。";
            case MODEL_AUTH_FAILED -> "检查视觉模型 API Key、Base URL 和账号权限。";
            case MODEL_RATE_LIMITED -> "稍后重试，或降低调用频率并检查服务商限流配置。";
            case MODEL_QUOTA_EXCEEDED -> "检查模型服务余额、免费额度或账号配额后重试。";
            case MODEL_TIMEOUT -> "检查网络和模型服务状态，必要时提高单页超时时间。";
            case MODEL_NETWORK_FAILED -> "检查网络、代理和模型服务地址后重试。";
            case MODEL_UNSUPPORTED_MEDIA -> "选择支持图片输入的多模态模型后重新解析。";
            case MODEL_PROVIDER_FAILED -> "检查模型服务状态和技术详情，稍后重新解析。";
            case MODEL_EMPTY_RESPONSE -> "确认所选模型支持图片识别，并使用 OCR 测试验证配置。";
            case DATABASE_WRITE_FAILED -> "检查本地数据目录权限和磁盘空间后重试。";
            case INDEX_WRITE_FAILED -> "检查索引目录权限和磁盘空间，然后执行补写或重建索引。";
            case LEGACY_FAILURE, UNKNOWN_FAILURE -> "查看技术详情和应用日志后重试。";
        };
    }

    private static DocumentFailureCode parseCode(String value) {
        if (value == null || value.isBlank()) {
            return DocumentFailureCode.LEGACY_FAILURE;
        }
        try {
            return DocumentFailureCode.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return DocumentFailureCode.UNKNOWN_FAILURE;
        }
    }
}
