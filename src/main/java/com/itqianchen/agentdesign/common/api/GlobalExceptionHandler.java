package com.itqianchen.agentdesign.common.api;


import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException;
import com.itqianchen.agentdesign.domain.exception.graph.KnowledgeGraphException;
import com.itqianchen.agentdesign.domain.exception.knowledge.KnowledgeMaintenanceException;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.exception.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.exception.search.SearchIndexException;
import com.itqianchen.agentdesign.domain.exception.websearch.WebSearchConfigurationException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * HTTP API 的统一错误响应边界。
 *
 * <p>普通 JSON 接口返回 ApiResponse 错误体；SSE 响应一旦进入 text/event-stream，就不能再写 JSON，
 * 否则会触发二次 HttpMessageConverter 异常并污染日志。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 本地文件解析失败属于用户可修正输入，错误原因直接返回给前端展示。
     *
     * @param ex 解析异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(DocumentParseException.class)
    public ResponseEntity<?> handleDocumentParseException(DocumentParseException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * Embedding 未配置时返回稳定业务错误码，前端据此提示向量/混合检索不可用。
     *
     * @param ex Embedding 不可用异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<?> handleEmbeddingUnavailable(EmbeddingUnavailableException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.EMBEDDING_UNAVAILABLE, ex.getMessage());
    }

    /**
     * 模型配置错误通常由设置页输入触发，返回 400 保持可恢复交互。
     *
     * @param ex 模型配置异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(ModelConfigurationException.class)
    public ResponseEntity<?> handleModelConfiguration(ModelConfigurationException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.MODEL_CONFIGURATION, ex.getMessage());
    }

    /**
     * 联网搜索配置错误由用户设置触发，返回 400 让前端引导到设置页修正。
     *
     * @param ex 联网搜索配置异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(WebSearchConfigurationException.class)
    public ResponseEntity<?> handleWebSearchConfiguration(
            WebSearchConfigurationException ex,
            HttpServletResponse response
    ) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 索引异常需要服务端日志保留堆栈，前端只接收可展示的错误消息。
     *
     * @param ex 搜索索引异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(SearchIndexException.class)
    public ResponseEntity<?> handleSearchIndexException(SearchIndexException ex, HttpServletResponse response) {
        log.error("search_index_error", ex);
        return errorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.SEARCH_INDEX_ERROR, ex.getMessage());
    }

    /**
     * 知识图谱生成失败由用户操作触发，返回 400 让前端保留当前页面状态并展示原因。
     *
     * @param ex 知识图谱异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(KnowledgeGraphException.class)
    public ResponseEntity<?> handleKnowledgeGraphException(KnowledgeGraphException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 维护队列操作失败属于用户可恢复状态，直接返回原因给前端展示。
     *
     * @param ex 维护任务异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(KnowledgeMaintenanceException.class)
    public ResponseEntity<?> handleKnowledgeMaintenanceException(
            KnowledgeMaintenanceException ex,
            HttpServletResponse response
    ) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, ex.getMessage());
    }

    /**
     * 资源不存在使用统一 NOT_FOUND code，避免前端为每个模块维护不同缺省文案。
     *
     * @param ex 资源不存在异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<?> handleResourceNotFound(ResourceNotFoundException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, ex.getMessage());
    }

    /**
     * Bean Validation 只返回首个字段错误，避免长列表覆盖表单当前焦点。
     *
     * @param ex 参数校验异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletResponse response) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_ERROR, message);
    }

    /**
     * JSON 解析失败不透出框架异常内容，避免把反序列化细节暴露给前端。
     *
     * @param ex JSON 解析异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletResponse response) {
        return errorResponse(response, HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Request body is not valid JSON");
    }

    /**
     * 异步请求超时通常发生在 SSE 连接上，只关闭响应，不再尝试包装 JSON 错误体。
     *
     * @param ex 异步超时异常
     * @return 空响应
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncRequestTimeout(AsyncRequestTimeoutException ex) {
        // SSE 响应头可能已经是 text/event-stream，不能再用 ApiResponse 写 JSON。
        // 返回空响应可以避免二次 HttpMessageConverter 报错污染日志。
        log.warn("async_request_timeout");
        return ResponseEntity.noContent().build();
    }

    /**
     * 备份恢复错误使用稳定 HTTP 状态，且不回显底层路径、ZIP 条目内容或 SQLite 异常。
     */
    @ExceptionHandler(DataProtectionException.class)
    public ResponseEntity<?> handleDataProtectionException(
            DataProtectionException ex,
            HttpServletResponse response
    ) {
        return switch (ex.reason()) {
            case INVALID_PACKAGE -> errorResponse(
                    response, HttpStatus.UNPROCESSABLE_ENTITY, ApiErrorCode.INVALID_BACKUP, ex.getMessage()
            );
            case CONFLICT -> errorResponse(
                    response, HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, ex.getMessage()
            );
            case NOT_FOUND -> errorResponse(
                    response, HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, ex.getMessage()
            );
            case INSUFFICIENT_STORAGE -> errorResponse(
                    response, HttpStatus.INSUFFICIENT_STORAGE, ApiErrorCode.INSUFFICIENT_STORAGE, ex.getMessage()
            );
            case IO_FAILURE -> {
                log.error("data_protection_io_failure", ex);
                yield errorResponse(
                        response, HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, ex.getMessage()
                );
            }
        };
    }

    /**
     * 浏览器刷新、路由切换或关闭页面时，SSE 输出流可能已经不可写。
     *
     * <p>这类断开不是服务端业务失败，降为 debug，避免掩盖真正需要处理的数据库或索引错误。</p>
     *
     * @param ex 异步响应不可写异常
     * @return 空响应
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<Void> handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        log.debug("async_request_not_usable", ex);
        return ResponseEntity.noContent().build();
    }

    /**
     * 未预期异常统一隐藏内部细节。
     *
     * <p>堆栈只写入服务端日志，避免把本地路径、密钥或第三方响应泄露到前端。</p>
     *
     * @param ex 未预期异常
     * @param response 当前 HTTP 响应
     * @return API 错误响应或空 SSE 响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex, HttpServletResponse response) {
        if (isEventStreamResponse(response)) {
            // SSE 响应一旦进入 text/event-stream，就不能再写 JSON ApiResponse。
            // 真实错误应尽量在 ChatSseEventMapper 中转成 SSE error 事件；这里仅兜底关闭。
            log.warn("unexpected_sse_error", ex);
            return ResponseEntity.noContent().build();
        }
        log.error("unexpected_api_error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ApiErrorCode.INTERNAL_ERROR, "Internal server error"));
    }

    /**
     * 构造统一错误响应，并在 SSE 已开始时降级为空响应。
     *
     * <p>SSE 响应头一旦写出，继续返回 JSON 会触发 Spring MVC 二次转换异常；这里集中处理该兼容边界。</p>
     *
     * @param response 当前 HTTP 响应
     * @param status HTTP 状态码
     * @param code 前端识别用的稳定错误码
     * @param message 可展示错误消息
     * @return JSON 错误响应或空 SSE 响应
     */
    private ResponseEntity<?> errorResponse(
            HttpServletResponse response,
            HttpStatus status,
            ApiErrorCode code,
            String message
    ) {
        if (isEventStreamResponse(response)) {
            // 已经进入 SSE 响应时返回 JSON 只会产生二次 HttpMessageConverter 错误。
            log.warn("sse_api_error_skipped code={} message={}", code, message);
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(status).body(ApiResponse.error(code, message));
    }

    /**
     * 判断当前响应是否已经进入 SSE。
     *
     * @param response 当前 HTTP 响应
     * @return 是否为 text/event-stream
     */
    private static boolean isEventStreamResponse(HttpServletResponse response) {
        String contentType = response.getContentType();
        return contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}


