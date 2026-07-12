package com.itqianchen.agentdesign.service.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 编解码文档失败诊断。
 *
 * <p>运行记录和文档表都保存 JSON 扩展信息；兼容逻辑集中在这里，避免业务服务各自处理旧格式。</p>
 */
@Component
public class DocumentFailureCodec {

    private static final Logger log = LoggerFactory.getLogger(DocumentFailureCodec.class);

    private final ObjectMapper objectMapper;

    public DocumentFailureCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encodeFailures(List<DocumentFailureResponse> failures) {
        if (failures == null || failures.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(failures);
        } catch (JsonProcessingException ex) {
            log.warn("document_failures_json_encode_failed failureCount={}", failures.size(), ex);
            return null;
        }
    }

    public List<DocumentFailureResponse> decodeFailures(String failuresJson) {
        if (failuresJson == null || failuresJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(DocumentFailureResponse.class)
                    .<List<DocumentFailureResponse>>readValue(failuresJson)
                    .stream()
                    .map(DocumentFailureResponse::normalized)
                    .toList();
        } catch (IOException ex) {
            log.warn("document_failures_json_decode_failed", ex);
            return List.of();
        }
    }

    public String encodeContext(DocumentFailureResponse failure) {
        FailureContext context = new FailureContext(
                failure.pageNumber(),
                failure.provider(),
                failure.modelName(),
                failure.httpStatus(),
                failure.providerErrorCode(),
                failure.completedPages(),
                failure.totalPages(),
                failure.resumePage()
        );
        if (context.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException ex) {
            log.warn("document_failure_context_encode_failed sourcePath={}", failure.sourcePath(), ex);
            return null;
        }
    }

    public DocumentFailureResponse fromDocument(KnowledgeDocument document) {
        if (document == null || !document.hasLastFailure()) {
            return null;
        }
        FailureContext context = decodeContext(document.lastFailureContextJson());
        return new DocumentFailureResponse(
                document.sourcePath(),
                document.lastFailureStage(),
                document.lastFailureCode(),
                document.lastFailureMessage(),
                document.lastFailureDetail(),
                DocumentFailureMessages.suggestion(document.lastFailureCode()),
                document.lastFailedAt(),
                context.pageNumber(),
                context.provider(),
                context.modelName(),
                context.httpStatus(),
                context.providerErrorCode(),
                context.completedPages(),
                context.totalPages(),
                context.resumePage()
        ).normalized();
    }

    private FailureContext decodeContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return FailureContext.EMPTY;
        }
        try {
            return objectMapper.readValue(contextJson, FailureContext.class);
        } catch (IOException ex) {
            log.warn("document_failure_context_decode_failed", ex);
            return FailureContext.EMPTY;
        }
    }

    private record FailureContext(
            Integer pageNumber,
            String provider,
            String modelName,
            Integer httpStatus,
            String providerErrorCode,
            Integer completedPages,
            Integer totalPages,
            Integer resumePage
    ) {
        private static final FailureContext EMPTY = new FailureContext(
                null, null, null, null, null, null, null, null
        );

        private boolean isEmpty() {
            return pageNumber == null
                    && provider == null
                    && modelName == null
                    && httpStatus == null
                    && providerErrorCode == null
                    && completedPages == null
                    && totalPages == null
                    && resumePage == null;
        }
    }
}
