package com.itqianchen.agentdesign.service.ocr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsRequest;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsResponse;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OCR 全局设置服务。
 *
 * <p>设置存入 app_settings 的 JSON 快照；视觉模型密钥归属 model_configs，避免 OCR 设置重复保存凭据。</p>
 */
@Service
public class OcrSettingsService implements OcrSettingsProvider {

    private static final Logger log = LoggerFactory.getLogger(OcrSettingsService.class);
    private static final String SETTINGS_KEY = "ocr.settings";

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;
    private final OcrEngineRegistry ocrEngineRegistry;
    private final ModelConfigService modelConfigService;

    public OcrSettingsService(
            AppSettingRepository appSettingRepository,
            ObjectMapper objectMapper,
            OcrEngineRegistry ocrEngineRegistry,
            ModelConfigService modelConfigService
    ) {
        this.appSettingRepository = appSettingRepository;
        this.objectMapper = objectMapper;
        this.ocrEngineRegistry = ocrEngineRegistry;
        this.modelConfigService = modelConfigService;
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
                requestedProvider(request.engine(), request.provider(), current.provider()),
                null,
                null,
                null,
                null,
                null,
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
                .map(this::normalize)
                .orElseGet(this::initializeDefaults);
    }

    @Transactional
    public OcrTestResponse test() {
        OcrSettingsSnapshot settings = snapshot();
        ModelConfig visionConfig = modelConfigService.activeVisionOrDefault();
        if (!settings.enabled()) {
            return new OcrTestResponse(false, "OCR 未启用。", settings.provider(), visionConfig.modelName());
        }
        if (!settings.visionModelConfigured()) {
            return new OcrTestResponse(false, "视觉识别模型 API Key 未配置。", settings.provider(),
                    visionConfig.modelName());
        }
        try {
            return ocrEngineRegistry.engineFor(settings.provider()).test(settings);
        } catch (OcrProviderException ex) {
            return new OcrTestResponse(false, ex.getMessage(), settings.provider(), visionConfig.modelName());
        }
    }

    private OcrSettingsSnapshot initializeDefaults() {
        OcrSettingsSnapshot defaults = normalize(storedDefaults());
        appSettingRepository.save(SETTINGS_KEY, encode(defaults));
        return defaults;
    }

    private String encode(OcrSettingsSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(new StoredSettings(
                    snapshot.enabled(),
                    snapshot.provider(),
                    null,
                    null,
                    null,
                    null,
                    null,
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

    private OcrSettingsSnapshot normalize(StoredSettings settings) {
        OcrProvider provider = normalizeStoredProvider(settings.provider());
        int maxPages = Math.clamp(defaultIfUnset(settings.maxPagesPerDocument(), 200), 1, 500);
        int timeoutSeconds = Math.clamp(defaultIfUnset(settings.timeoutPerPageSeconds(), 20), 3, 600);
        int monthlyBudget = Math.clamp(defaultIfUnset(settings.monthlyCallBudget(), 1000), 1, 1_000_000);
        boolean migratedFromBaidu = settings.provider() == OcrProvider.BAIDU_OCR;
        boolean enabled = settings.enabled() && provider == OcrProvider.MODEL_VISION && !migratedFromBaidu;
        return new OcrSettingsSnapshot(
                enabled,
                provider,
                modelConfigService.activeVisionOrDefault().hasApiKey(),
                maxPages,
                timeoutSeconds,
                monthlyBudget
        );
    }

    private static OcrProvider requestedProvider(
            OcrProvider engine,
            OcrProvider provider,
            OcrProvider fallback
    ) {
        OcrProvider requested = engine == null ? provider : engine;
        return requested == null ? fallback : requested;
    }

    private static OcrProvider normalizeStoredProvider(OcrProvider provider) {
        return provider == null || provider == OcrProvider.BAIDU_OCR ? OcrProvider.MODEL_VISION : provider;
    }

    private static int defaultIfUnset(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private static StoredSettings storedDefaults() {
        OcrSettingsSnapshot defaults = OcrSettingsSnapshot.defaults();
        return new StoredSettings(
                defaults.enabled(),
                defaults.provider(),
                null,
                null,
                null,
                null,
                null,
                defaults.maxPagesPerDocument(),
                defaults.timeoutPerPageSeconds(),
                defaults.monthlyCallBudget()
        );
    }

    private OcrSettingsResponse toResponse(OcrSettingsSnapshot snapshot) {
        ModelConfig visionConfig = modelConfigService.activeVisionOrDefault();
        return new OcrSettingsResponse(
                snapshot.enabled(),
                snapshot.provider(),
                snapshot.available(),
                new OcrSettingsResponse.VisionModelSettings(
                        visionConfig.id(),
                        visionConfig.provider().name(),
                        visionConfig.displayName(),
                        visionConfig.baseUrl(),
                        visionConfig.hasApiKey(),
                        visionConfig.modelName()
                ),
                new OcrSettingsResponse.Limits(
                        snapshot.maxPagesPerDocument(),
                        snapshot.timeoutPerPageSeconds(),
                        snapshot.monthlyCallBudget()
                )
        );
    }

    private record StoredSettings(
            boolean enabled,
            OcrProvider provider,
            String apiKey,
            String secretKey,
            String recognitionMode,
            String languageType,
            Boolean detectDirection,
            int maxPagesPerDocument,
            int timeoutPerPageSeconds,
            int monthlyCallBudget
    ) {
    }
}
