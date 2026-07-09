package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    private static final String SYSTEM_PROMPT = """
            你是一个用于知识库导入的 OCR 文本抽取器。
            只输出图片中可见的原文文字。
            尽量保留段落、换行、列表、表格的阅读顺序。
            不要解释、不要总结、不要翻译、不要补充图片中不存在的内容。
            如果没有可读文字，返回空字符串。
            """;
    private static final String PAGE_PROMPT = "请识别这张 PDF 页面图片中的文字，只返回原文。";
    private static final String TEST_PROMPT = "请识别图片中的测试文字，只返回原文。";

    private final ModelConfigService modelConfigService;
    private final AiRuntimeFactory aiRuntimeFactory;

    public ModelVisionOcrEngine(ModelConfigService modelConfigService, AiRuntimeFactory aiRuntimeFactory) {
        this.modelConfigService = modelConfigService;
        this.aiRuntimeFactory = aiRuntimeFactory;
    }

    @Override
    public boolean supports(OcrProvider provider) {
        return provider == OcrProvider.MODEL_VISION;
    }

    @Override
    public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
        ModelConfig config = modelConfigService.requireActiveVisionConfigured();
        String text = callVisionModel(config, PAGE_PROMPT, pageImage.imageBytes(), settings.timeoutPerPageSeconds());
        return text == null ? "" : text.trim();
    }

    @Override
    public OcrTestResponse test(OcrSettingsSnapshot settings) {
        ModelConfig config = modelConfigService.requireActiveVisionConfigured();
        try {
            String text = callVisionModel(config, TEST_PROMPT, testImage(), settings.timeoutPerPageSeconds());
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

    private String callVisionModel(ModelConfig config, String prompt, byte[] imageBytes, int timeoutSeconds) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> future = executor.submit(() -> aiRuntimeFactory.chatRuntime(config)
                .callImage(SYSTEM_PROMPT, prompt, imageBytes, MimeTypeUtils.IMAGE_PNG));
        try {
            return future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new OcrProviderException("视觉识别模型单页调用超时，请调高单页超时或检查模型服务。", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OcrProviderException("视觉识别模型调用被中断。", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ModelConfigurationException modelEx) {
                throw new OcrProviderException("视觉识别模型调用失败，请检查模型配置、网络或服务额度。", modelEx);
            }
            throw new OcrProviderException("视觉识别模型调用失败，请检查模型配置、网络或服务额度。", ex);
        } finally {
            executor.shutdownNow();
        }
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
