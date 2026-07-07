package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 百度智能云 OCR 引擎。
 *
 * <p>该引擎只接收 PDFBox 渲染后的单页图片；不会读取或上传用户原始 PDF 文件。</p>
 */
@Component
public class BaiduOcrEngine implements OcrEngine {

    private static final long TOKEN_EXPIRY_SAFETY_WINDOW_MS = 60_000L;
    private static final Set<Integer> QUOTA_OR_RATE_LIMIT_CODES = Set.of(17, 18, 19, 111, 216202);

    private final BaiduOcrClient baiduOcrClient;
    private volatile CachedAccessToken cachedAccessToken;

    public BaiduOcrEngine(BaiduOcrClient baiduOcrClient) {
        this.baiduOcrClient = baiduOcrClient;
    }

    @Override
    public boolean supports(OcrProvider provider) {
        return provider == OcrProvider.BAIDU_OCR;
    }

    @Override
    public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
        if (!settings.available()) {
            throw new OcrProviderException("百度 OCR 未启用或密钥未配置。");
        }
        String accessToken = accessToken(settings);
        String imageBase64 = Base64.getEncoder().encodeToString(pageImage.imageBytes());
        BaiduOcrRecognitionResponse response = baiduOcrClient.recognizeImage(new BaiduOcrRecognitionRequest(
                accessToken,
                imageBase64,
                settings.recognitionMode(),
                settings.languageType(),
                settings.detectDirection(),
                settings.timeoutPerPageSeconds()
        ));
        if (response.hasError()) {
            throw new OcrProviderException(messageForError(response.errorCode()));
        }
        return response.words() == null ? "" : String.join("\n", response.words()).trim();
    }

    @Override
    public OcrTestResponse test(OcrSettingsSnapshot settings) {
        accessToken(settings);
        return new OcrTestResponse(true, "百度 OCR 鉴权通过。", settings.provider(), settings.recognitionMode());
    }

    private String accessToken(OcrSettingsSnapshot settings) {
        String credentialsFingerprint = credentialsFingerprint(settings);
        CachedAccessToken current = cachedAccessToken;
        long now = System.currentTimeMillis();
        if (current != null
                && current.expiresAtMillis() > now
                && current.credentialsFingerprint().equals(credentialsFingerprint)) {
            return current.token();
        }
        synchronized (this) {
            current = cachedAccessToken;
            now = System.currentTimeMillis();
            if (current != null
                    && current.expiresAtMillis() > now
                    && current.credentialsFingerprint().equals(credentialsFingerprint)) {
                return current.token();
            }
            BaiduAccessToken fetched = baiduOcrClient.fetchAccessToken(
                    settings.apiKey(),
                    settings.secretKey(),
                    settings.timeoutPerPageSeconds()
            );
            long expiresAt = now + Math.max(60_000L,
                    fetched.expiresInSeconds() * 1000L - TOKEN_EXPIRY_SAFETY_WINDOW_MS);
            cachedAccessToken = new CachedAccessToken(fetched.token(), credentialsFingerprint, expiresAt);
            return fetched.token();
        }
    }

    private static String messageForError(Integer errorCode) {
        if (errorCode != null && QUOTA_OR_RATE_LIMIT_CODES.contains(errorCode)) {
            return "百度 OCR 额度或频率限制，请稍后重试或调整调用预算。";
        }
        return "百度 OCR 识别失败，请检查图片质量、接口权限或服务状态。";
    }

    private static String credentialsFingerprint(OcrSettingsSnapshot settings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((settings.apiKey() + '\0' + settings.secretKey())
                    .getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for OCR credential cache", ex);
        }
    }

    private record CachedAccessToken(
            String token,
            String credentialsFingerprint,
            long expiresAtMillis
    ) {
    }
}
