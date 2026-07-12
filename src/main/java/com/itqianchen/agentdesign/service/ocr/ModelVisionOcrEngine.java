package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.properties.ocr.OcrPromptProperties;
import com.itqianchen.agentdesign.service.document.DocumentFailureSanitizer;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 使用多模态视觉模型执行 OCR。
 *
 * <p>该引擎只负责把页面图片交给用户配置的 VISION 模型；密钥、Base URL 和模型名由模型配置统一管理。</p>
 */
@Component
public class ModelVisionOcrEngine implements OcrEngine {

    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");
    private static final Pattern PROVIDER_CODE_PATTERN = Pattern.compile(
            "(?i)(?:error[_ ]?code|code)\\s*[:=]\\s*[\"']?([a-z0-9_.-]+)"
    );

    private final ModelConfigService modelConfigService;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final OcrPromptProperties promptProperties;

    public ModelVisionOcrEngine(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            OcrPromptProperties promptProperties
    ) {
        this.modelConfigService = modelConfigService;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.promptProperties = promptProperties;
    }

    @Override
    public boolean supports(OcrProvider provider) {
        return provider == OcrProvider.MODEL_VISION;
    }

    @Override
    public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
        ModelConfig config = requireVisionConfig(pageImage.pageNumber());
        String text = callVisionModel(
                config,
                promptProperties.page(),
                pageImage.imageBytes(),
                settings.timeoutPerPageSeconds(),
                pageImage.pageNumber()
        );
        return text == null ? "" : text.trim();
    }

    @Override
    public String checkpointSignature(OcrSettingsSnapshot settings) {
        ModelConfig config = requireVisionConfig(null);
        String material = String.join("\n",
                "MODEL_VISION:v1",
                config.provider().name(),
                config.baseUrl(),
                config.modelName(),
                String.valueOf(config.temperature()),
                promptProperties.system(),
                promptProperties.page()
        );
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    @Override
    public OcrTestResponse test(OcrSettingsSnapshot settings) {
        ModelConfig config = requireVisionConfig(null);
        try {
            String text = callVisionModel(
                    config,
                    promptProperties.test(),
                    testImage(),
                    settings.timeoutPerPageSeconds(),
                    null
            );
            if (!containsExpectedTestText(text)) {
                return new OcrTestResponse(
                        false,
                        "视觉模型未正确识别测试图片，请确认所选模型支持图片输入。",
                        settings.provider(),
                        config.modelName()
                );
            }
            return new OcrTestResponse(true, "视觉模型图片输入测试成功。", settings.provider(), config.modelName());
        } catch (IOException ex) {
            throw new OcrProviderException("视觉模型测试图片生成失败。", ex);
        }
    }

    private ModelConfig requireVisionConfig(Integer pageNumber) {
        try {
            return modelConfigService.requireActiveVisionConfigured();
        } catch (ModelConfigurationException ex) {
            ModelConfig config = modelConfigService.activeVisionOrDefault();
            throw new OcrProviderException(
                    DocumentFailureStage.MODEL_CONFIG,
                    DocumentFailureCode.MODEL_NOT_CONFIGURED,
                    "视觉识别模型未正确配置。",
                    ex.getMessage(),
                    pageNumber,
                    config.provider().name(),
                    config.modelName(),
                    null,
                    null,
                    ex
            );
        }
    }

    private String callVisionModel(
            ModelConfig config,
            String prompt,
            byte[] imageBytes,
            int timeoutSeconds,
            Integer pageNumber
    ) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> future = executor.submit(() -> aiRuntimeFactory.chatRuntime(config)
                .callImage(promptProperties.system(), prompt, imageBytes, MimeTypeUtils.IMAGE_PNG));
        try {
            return future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw providerException(
                    config,
                    pageNumber,
                    DocumentFailureCode.MODEL_TIMEOUT,
                    "视觉模型调用超时。",
                    "timeoutSeconds=" + Math.max(1, timeoutSeconds),
                    null,
                    null,
                    ex
            );
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw providerException(
                    config,
                    pageNumber,
                    DocumentFailureCode.MODEL_NETWORK_FAILED,
                    "视觉模型调用被中断。",
                    ex.getMessage(),
                    null,
                    null,
                    ex
            );
        } catch (ExecutionException ex) {
            throw classifyProviderFailure(config, pageNumber, ex.getCause() == null ? ex : ex.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static OcrProviderException classifyProviderFailure(
            ModelConfig config,
            Integer pageNumber,
            Throwable cause
    ) {
        String rawMessage = providerFailureDetail(cause);
        String normalized = rawMessage.toLowerCase(Locale.ROOT);
        Integer httpStatus = extractHttpStatus(rawMessage);
        String providerCode = extractProviderCode(rawMessage);
        DocumentFailureCode code;
        String message;
        if ((httpStatus != null && (httpStatus == 401 || httpStatus == 403))
                || containsAny(normalized, "unauthorized", "forbidden", "invalid api key", "bad key", "authentication")) {
            code = DocumentFailureCode.MODEL_AUTH_FAILED;
            message = "视觉模型鉴权失败。";
        } else if (containsAny(normalized, "quota", "insufficient balance", "credit", "余额", "额度")) {
            code = DocumentFailureCode.MODEL_QUOTA_EXCEEDED;
            message = "视觉模型额度不足。";
        } else if ((httpStatus != null && httpStatus == 429)
                || containsAny(normalized, "rate limit", "too many requests")) {
            code = DocumentFailureCode.MODEL_RATE_LIMITED;
            message = "视觉模型调用频率受限。";
        } else if (containsAny(normalized, "timeout", "timed out", "read timed out")) {
            code = DocumentFailureCode.MODEL_TIMEOUT;
            message = "视觉模型调用超时。";
        } else if (containsAny(normalized, "image", "media", "multimodal", "vision")
                && containsAny(normalized, "unsupported", "not support", "invalid")) {
            code = DocumentFailureCode.MODEL_UNSUPPORTED_MEDIA;
            message = "当前视觉模型不支持图片输入。";
        } else if (containsAny(normalized, "connection", "connect", "dns", "network", "refused", "unreachable")) {
            code = DocumentFailureCode.MODEL_NETWORK_FAILED;
            message = "无法连接视觉模型服务。";
        } else {
            code = DocumentFailureCode.MODEL_PROVIDER_FAILED;
            message = "视觉模型服务调用失败。";
        }
        return providerException(
                config,
                pageNumber,
                code,
                message,
                rawMessage,
                httpStatus,
                providerCode,
                cause
        );
    }

    private static OcrProviderException providerException(
            ModelConfig config,
            Integer pageNumber,
            DocumentFailureCode code,
            String message,
            String rawDetail,
            Integer httpStatus,
            String providerCode,
            Throwable cause
    ) {
        String sanitizedDetail = DocumentFailureSanitizer.sanitize(rawDetail);
        String detail = String.join(
                " / ",
                config.provider().name(),
                config.modelName(),
                httpStatus == null ? "HTTP -" : "HTTP " + httpStatus,
                providerCode == null ? "code -" : providerCode,
                sanitizedDetail == null ? "无服务商详情" : sanitizedDetail
        );
        return new OcrProviderException(
                DocumentFailureStage.MODEL_CALL,
                code,
                message,
                detail,
                pageNumber,
                config.provider().name(),
                config.modelName(),
                httpStatus,
                providerCode,
                cause
        );
    }

    private static Integer extractHttpStatus(String message) {
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static String extractProviderCode(String message) {
        Matcher matcher = PROVIDER_CODE_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 汇总有限层级的 cause 消息，识别被 SDK 包装在底层的 HTTP 状态和 Provider 错误码。
     */
    private static String providerFailureDetail(Throwable cause) {
        StringBuilder detail = new StringBuilder();
        Throwable current = cause;
        int depth = 0;
        while (current != null && depth < 8) {
            if (!detail.isEmpty()) {
                detail.append(" | caused by: ");
            }
            detail.append(current.getClass().getSimpleName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                detail.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return detail.toString();
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExpectedTestText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        return normalized.contains("OCRTEST");
    }

    private static byte[] testImage() throws IOException {
        BufferedImage image = new BufferedImage(320, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.drawString("OCR TEST", 32, 56);
        } finally {
            graphics.dispose();
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return outputStream.toByteArray();
        }
    }
}
