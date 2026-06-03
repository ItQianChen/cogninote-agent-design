package com.itqianchen.agentdesign.service.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigService {

    private final ModelConfigRepository modelConfigRepository;

    public ModelConfigService(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    public ModelConfig activeOrDefault() {
        long now = System.currentTimeMillis();
        return modelConfigRepository.findActive()
                .map(ModelConfigService::normalizeLoadedConfig)
                .orElseGet(() -> new ModelConfig(
                        ModelConfigDefaults.ACTIVE_CONFIG_ID,
                        ModelConfigDefaults.PROVIDER,
                        ModelConfigDefaults.DISPLAY_NAME,
                        ModelConfigDefaults.BASE_URL,
                        "",
                        ModelConfigDefaults.CHAT_MODEL,
                        ModelConfigDefaults.EMBEDDING_MODEL,
                        ModelConfigDefaults.EMBEDDING_DIMENSIONS,
                        ModelConfigDefaults.TEMPERATURE,
                        ModelConfigDefaults.TOP_K,
                        now,
                        now
                ));
    }

    public ModelConfig requireConfigured() {
        ModelConfig config = activeOrDefault();
        if (!config.hasApiKey()) {
            throw new ModelConfigurationException("API Key is not configured");
        }
        return config;
    }

    @Transactional
    public ModelConfig save(ModelConfigRequest request) {
        ModelConfig existing = activeOrDefault();
        long now = System.currentTimeMillis();
        ModelConfig config = mergeRequest(request, existing, now);
        return modelConfigRepository.saveActive(config);
    }

    public ModelConfig connectionTestConfig(ModelConfigRequest request) {
        ModelConfig config = mergeRequest(request, activeOrDefault(), System.currentTimeMillis());
        if (!config.hasApiKey()) {
            throw new ModelConfigurationException("API Key is required for connection test");
        }
        return config;
    }

    private static String normalizeApiKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    private static ModelConfig mergeRequest(ModelConfigRequest request, ModelConfig existing, long now) {
        ModelProvider provider = normalizeProvider(request.provider());
        String requestedApiKey = normalizeApiKey(request.apiKey());
        // 前端默认用 password 隐藏已保存的 Key，但保存时仍允许留空复用旧 Key。
        // 这让用户可以只改模型参数，不必每次重新粘贴密钥。
        String apiKey = requestedApiKey.isBlank() ? existing.apiKey() : requestedApiKey;
        return new ModelConfig(
                ModelConfigDefaults.ACTIVE_CONFIG_ID,
                provider,
                normalizeDisplayName(request.displayName()),
                normalizeBaseUrl(provider, request.baseUrl()),
                apiKey,
                request.chatModel().trim(),
                request.embeddingModel().trim(),
                request.embeddingDimensions() == null
                        ? ModelConfigDefaults.EMBEDDING_DIMENSIONS
                        : request.embeddingDimensions(),
                request.temperature() == null
                        ? ModelConfigDefaults.TEMPERATURE
                        : request.temperature(),
                request.topK() == null
                        ? ModelConfigDefaults.TOP_K
                        : request.topK(),
                existing.createdAt(),
                now
        );
    }

    private static ModelProvider normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return ModelConfigDefaults.PROVIDER;
        }
        try {
            return ModelProvider.valueOf(provider.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ModelConfigurationException("Unsupported model provider: " + provider);
        }
    }

    private static ModelConfig normalizeLoadedConfig(ModelConfig config) {
        if (config.provider() != ModelProvider.DASHSCOPE) {
            return config;
        }
        String normalizedBaseUrl = DashScopeBaseUrls.normalizeConfigBaseUrl(config.baseUrl());
        if (normalizedBaseUrl.equals(config.baseUrl())) {
            return config;
        }
        return new ModelConfig(
                config.id(),
                config.provider(),
                config.displayName(),
                normalizedBaseUrl,
                config.apiKey(),
                config.chatModel(),
                config.embeddingModel(),
                config.embeddingDimensions(),
                config.temperature(),
                config.topK(),
                config.createdAt(),
                config.updatedAt()
        );
    }

    private static String normalizeDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return ModelConfigDefaults.DISPLAY_NAME;
        }
        return displayName.trim();
    }

    private static String normalizeBaseUrl(ModelProvider provider, String baseUrl) {
        return switch (provider) {
            case DASHSCOPE -> DashScopeBaseUrls.normalizeConfigBaseUrl(ModelConfigDefaults.BASE_URL);
            case OPENAI_COMPATIBLE -> OpenAiCompatibleUrls.normalizeBaseUrl(baseUrl);
        };
    }
}


