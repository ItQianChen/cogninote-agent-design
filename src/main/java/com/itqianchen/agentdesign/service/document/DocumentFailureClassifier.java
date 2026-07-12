package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.service.ocr.OcrProviderException;
import java.nio.file.Path;
import java.util.Locale;

/** 将处理异常转换为稳定、脱敏的文档失败诊断。 */
public final class DocumentFailureClassifier {

    private DocumentFailureClassifier() {
    }

    public static DocumentFailureResponse classify(
            Path sourcePath,
            DocumentFailureStage fallbackStage,
            RuntimeException exception,
            long occurredAt
    ) {
        String normalizedPath = sourcePath == null ? null : sourcePath.toString();
        if (exception instanceof OcrProviderException ocrException) {
            return ocrException.toFailure(normalizedPath, occurredAt);
        }
        if (exception instanceof PdfOcrRequiredException) {
            return failure(
                    normalizedPath,
                    DocumentFailureStage.OCR,
                    DocumentFailureCode.OCR_REQUIRED,
                    "PDF 没有可抽取文本层，需要启用 OCR。",
                    exception,
                    occurredAt
            );
        }

        DocumentFailureCode code = classifyCode(fallbackStage, exception);
        return failure(
                normalizedPath,
                fallbackStage == null ? DocumentFailureStage.UNKNOWN : fallbackStage,
                code,
                message(code),
                exception,
                occurredAt
        );
    }

    private static DocumentFailureResponse failure(
            String sourcePath,
            DocumentFailureStage stage,
            DocumentFailureCode code,
            String message,
            RuntimeException exception,
            long occurredAt
    ) {
        return DocumentFailureResponse.of(
                sourcePath,
                stage,
                code,
                message,
                DocumentFailureSanitizer.sanitize(exception.getMessage()),
                DocumentFailureMessages.suggestion(code.name()),
                occurredAt,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static DocumentFailureCode classifyCode(
            DocumentFailureStage stage,
            RuntimeException exception
    ) {
        String message = exception.getMessage() == null
                ? ""
                : exception.getMessage().toLowerCase(Locale.ROOT);
        return switch (stage == null ? DocumentFailureStage.UNKNOWN : stage) {
            case SCAN -> message.contains("not a directory") || message.contains("does not exist")
                    ? DocumentFailureCode.FOLDER_NOT_FOUND
                    : DocumentFailureCode.FOLDER_SCAN_FAILED;
            case READ -> message.contains("metadata") || message.contains("hash")
                    ? DocumentFailureCode.FILE_METADATA_FAILED
                    : DocumentFailureCode.FILE_READ_FAILED;
            case PARSE -> {
                if (message.contains("encrypt") || message.contains("password")) {
                    yield DocumentFailureCode.DOCUMENT_ENCRYPTED;
                }
                if (message.contains("no usable text") || message.contains("empty")) {
                    yield DocumentFailureCode.NO_USABLE_TEXT;
                }
                yield DocumentFailureCode.DOCUMENT_CORRUPTED;
            }
            case OCR -> {
                if (message.contains("page limit")) {
                    yield DocumentFailureCode.OCR_PAGE_LIMIT;
                }
                if (message.contains("no usable text") || message.contains("produced no")) {
                    yield DocumentFailureCode.OCR_EMPTY_RESULT;
                }
                yield DocumentFailureCode.OCR_RENDER_FAILED;
            }
            case MODEL_CONFIG -> DocumentFailureCode.MODEL_NOT_CONFIGURED;
            case MODEL_CALL -> DocumentFailureCode.MODEL_PROVIDER_FAILED;
            case CHUNK -> DocumentFailureCode.CHUNK_EMPTY;
            case PERSIST -> DocumentFailureCode.DATABASE_WRITE_FAILED;
            case INDEX -> DocumentFailureCode.INDEX_WRITE_FAILED;
            case UNKNOWN -> DocumentFailureCode.UNKNOWN_FAILURE;
        };
    }

    private static String message(DocumentFailureCode code) {
        return switch (code) {
            case FOLDER_NOT_FOUND -> "知识库目录不存在或不是有效目录。";
            case FOLDER_SCAN_FAILED -> "扫描知识库目录失败。";
            case FILE_METADATA_FAILED -> "读取文件元数据失败。";
            case FILE_READ_FAILED -> "读取文件内容失败。";
            case DOCUMENT_CORRUPTED -> "文档格式损坏或当前解析器无法读取。";
            case DOCUMENT_ENCRYPTED -> "文档已加密，当前无法解析。";
            case NO_USABLE_TEXT -> "文档没有可用于知识库的正文。";
            case OCR_REQUIRED -> "PDF 没有可抽取文本层，需要启用 OCR。";
            case OCR_PAGE_LIMIT -> "PDF 页数超过当前 OCR 上限。";
            case OCR_RENDER_FAILED -> "PDF 页面渲染失败。";
            case OCR_EMPTY_RESULT -> "OCR 没有识别出可用文字。";
            case MODEL_NOT_CONFIGURED -> "视觉识别模型未正确配置。";
            case MODEL_AUTH_FAILED -> "视觉模型鉴权失败。";
            case MODEL_RATE_LIMITED -> "视觉模型调用频率受限。";
            case MODEL_QUOTA_EXCEEDED -> "视觉模型额度不足。";
            case MODEL_TIMEOUT -> "视觉模型调用超时。";
            case MODEL_NETWORK_FAILED -> "无法连接视觉模型服务。";
            case MODEL_UNSUPPORTED_MEDIA -> "当前模型不支持图片输入。";
            case MODEL_PROVIDER_FAILED -> "视觉模型服务调用失败。";
            case MODEL_EMPTY_RESPONSE -> "视觉模型没有返回可用文字。";
            case CHUNK_EMPTY -> "文档解析后没有生成有效片段。";
            case DATABASE_WRITE_FAILED -> "文档结果写入本地数据库失败。";
            case INDEX_WRITE_FAILED -> "文档写入检索索引失败。";
            case LEGACY_FAILURE -> "文档处理失败。";
            case UNKNOWN_FAILURE -> "文档处理发生未知错误。";
        };
    }
}
