package com.itqianchen.agentdesign.service.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsRequest;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsResponse;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OCR 全局设置服务。
 *
 * <p>设置存入 app_settings 的 JSON 快照；密钥只允许在本服务和 Provider 调用链内流转。</p>
 */
@Service
public class OcrSettingsService implements OcrSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(OcrSettingsService.class);
    private static final String SETTINGS_KEY = "ocr.settings";

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;
    private final OcrEngineRegistry ocrEngineRegistry;

    public OcrSettingsService(
            AppSettingRepository appSettingRepository,
            ObjectMapper objectMapper,
            OcrEngineRegistry ocrEngineRegistry
    ) {
        this.appSettingRepository = appSettingRepository;
        this.objectMapper = objectMapper;
        this.ocrEngineRegistry = ocrEngineRegistry;
    }

    @Transactional
    public OcrSettingsResponse settings() {
        return toResponse(snapshot());
    }

    @Transactional
    public OcrSettingsResponse update(OcrSettingsRequest request) {
        OcrSettingsSnapshot current = snapshot();
        OcrSettingsSnapshot updated = normalize(new StoredSettings(
                request.enabled() == null ? current.enabled() : request.enabled(),
                request.provider() == null ? current.provider() : request.provider(),
                request.baidu() == null || request.baidu().apiKey() == null
                        ? current.apiKey()
                        : safeTrim(request.baidu().apiKey()),
                request.baidu() == null || request.baidu().secretKey() == null
                        ? current.secretKey()
                        : safeTrim(request.baidu().secretKey()),
                request.baidu() == null || request.baidu().recognitionMode() == null
                        ? current.recognitionMode()
                        : request.baidu().recognitionMode(),
                request.baidu() == null || request.baidu().languageType() == null
                        ? current.languageType()
                        : request.baidu().languageType(),
                request.baidu() == null || request.baidu().detectDirection() == null
                        ? current.detectDirection()
                        : request.baidu().detectDirection(),
                request.limits() == null || request.limits().maxPagesPerDocument() == null
                        ? current.maxPagesPerDocument()
                        : request.limits().maxPagesPerDocument(),
                request.limits() == null || request.limits().timeoutPerPageSeconds() == null
                        ? current.timeoutPerPageSeconds()
                        : request.limits().timeoutPerPageSeconds(),
                request.limits() == null || request.limits().monthlyCallBudget() == null
                        ? current.monthlyCallBudget()
                        : request.limits().monthlyCallBudget()
        ));
        appSettingRepository.save(SETTINGS_KEY, encode(updated));
        return toResponse(updated);
    }

    @Transactional
    @Override
    public OcrSettingsSnapshot snapshot() {
        return appSettingRepository.findValue(SETTINGS_KEY)
                .map(this::decode)
                .map(OcrSettingsService::normalize)
                .orElseGet(this::initializeDefaults);
    }

    @Transactional
    public OcrTestResponse test() {
        OcrSettingsSnapshot settings = snapshot();
        if (!settings.enabled()) {
            return new OcrTestResponse(false, "OCR 未启用。", settings.provider(), settings.recognitionMode());
        }
        if (!settings.credentialsConfigured()) {
            return new OcrTestResponse(false, "百度 OCR API Key 或 Secret Key 未配置。", settings.provider(),
                    settings.recognitionMode());
        }
        try {
            return ocrEngineRegistry.engineFor(settings.provider()).test(settings);
        } catch (OcrProviderException ex) {
            return new OcrTestResponse(false, ex.getMessage(), settings.provider(), settings.recognitionMode());
        }
    }

    private OcrSettingsSnapshot initializeDefaults() {
        OcrSettingsSnapshot defaults = OcrSettingsSnapshot.defaults();
        appSettingRepository.save(SETTINGS_KEY, encode(defaults));
        return defaults;
    }

    private String encode(OcrSettingsSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(new StoredSettings(
                    snapshot.enabled(),
                    snapshot.provider(),
                    snapshot.apiKey(),
                    snapshot.secretKey(),
                    snapshot.recognitionMode(),
                    snapshot.languageType(),
                    snapshot.detectDirection(),
                    snapshot.maxPagesPerDocument(),
                    snapshot.timeoutPerPageSeconds(),
                    snapshot.monthlyCallBudget()
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode OCR settings", ex);
        }
    }

    private StoredSettings decode(String json) {
        try {
            return objectMapper.readValue(json, StoredSettings.class);
        } catch (JsonProcessingException ex) {
            log.warn("ocr_settings_decode_failed");
            return storedDefaults();
        }
    }

    private static OcrSettingsSnapshot normalize(StoredSettings settings) {
        OcrProvider provider = settings.provider() == null ? OcrProvider.BAIDU_OCR : settings.provider();
        String apiKey = safeTrim(settings.apiKey());
        String secretKey = safeTrim(settings.secretKey());
        BaiduOcrRecognitionMode mode = settings.recognitionMode() == null
                ? BaiduOcrRecognitionMode.STANDARD
                : settings.recognitionMode();
        String languageType = safeTrim(settings.languageType()).isBlank()
                ? "CHN_ENG"
                : safeTrim(settings.languageType()).toUpperCase();
        boolean detectDirection = settings.detectDirection();
        int maxPages = Math.clamp(settings.maxPagesPerDocument(), 1, 500);
        int timeoutSeconds = Math.clamp(settings.timeoutPerPageSeconds(), 3, 120);
        int monthlyBudget = Math.clamp(settings.monthlyCallBudget(), 1, 1_000_000);
        boolean enabled = settings.enabled() && !apiKey.isBlank() && !secretKey.isBlank();
        return new OcrSettingsSnapshot(
                enabled,
                provider,
                apiKey,
                secretKey,
                mode,
                languageType,
                detectDirection,
                maxPages,
                timeoutSeconds,
                monthlyBudget
        );
    }

    private static StoredSettings storedDefaults() {
        OcrSettingsSnapshot defaults = OcrSettingsSnapshot.defaults();
        return new StoredSettings(
                defaults.enabled(),
                defaults.provider(),
                defaults.apiKey(),
                defaults.secretKey(),
                defaults.recognitionMode(),
                defaults.languageType(),
                defaults.detectDirection(),
                defaults.maxPagesPerDocument(),
                defaults.timeoutPerPageSeconds(),
                defaults.monthlyCallBudget()
        );
    }

    private static OcrSettingsResponse toResponse(OcrSettingsSnapshot snapshot) {
        return new OcrSettingsResponse(
                snapshot.enabled(),
                snapshot.provider(),
                snapshot.available(),
                new OcrSettingsResponse.BaiduSettings(
                        snapshot.apiKey(),
                        snapshot.secretKey(),
                        !snapshot.apiKey().isBlank(),
                        !snapshot.secretKey().isBlank(),
                        snapshot.recognitionMode(),
                        snapshot.languageType(),
                        snapshot.detectDirection()
                ),
                new OcrSettingsResponse.Limits(
                        snapshot.maxPagesPerDocument(),
                        snapshot.timeoutPerPageSeconds(),
                        snapshot.monthlyCallBudget()
                )
        );
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredSettings(
            boolean enabled,
            OcrProvider provider,
            String apiKey,
            String secretKey,
            BaiduOcrRecognitionMode recognitionMode,
            String languageType,
            boolean detectDirection,
            int maxPagesPerDocument,
            int timeoutPerPageSeconds,
            int monthlyCallBudget
    ) {
    }
}
