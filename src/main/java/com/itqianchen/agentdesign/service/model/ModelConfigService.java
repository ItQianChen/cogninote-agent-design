package com.itqianchen.agentdesign.service.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import com.itqianchen.agentdesign.dto.model.ActiveModelConfigsResponse;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.dto.model.ModelConfigResponse;
import com.itqianchen.agentdesign.dto.model.ModelConfigSettingsResponse;
import com.itqianchen.agentdesign.dto.model.ModelConfigUpsertRequest;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigService {

    private final ModelConfigRepository modelConfigRepository;

    public ModelConfigService(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    public List<ModelConfig> list(ModelConfigRole role) {
        return modelConfigRepository.findAll(role).stream()
                .map(ModelConfigService::normalizeLoadedConfig)
                .toList();
    }

    @Transactional
    public ModelConfigSettingsResponse settingsSnapshot(ModelConfigRole role) {
        ensureRoleHasActiveConfig(ModelConfigRole.CHAT);
        ensureRoleHasActiveConfig(ModelConfigRole.EMBEDDING);
        return settingsSnapshot(role, Optional.empty());
    }

    public ModelConfig activeChatOrDefault() {
        return activeOrDefault(ModelConfigRole.CHAT);
    }

    public ModelConfig activeEmbeddingOrDefault() {
        return activeOrDefault(ModelConfigRole.EMBEDDING);
    }

    public ModelConfig activeOrDefault(ModelConfigRole role) {
        return modelConfigRepository.findActive(role)
                .map(ModelConfigService::normalizeLoadedConfig)
                .orElseGet(() -> defaultConfig(role, true));
    }

    @Deprecated
    public ModelConfig activeOrDefault() {
        return activeChatOrDefault();
    }

    public ModelConfig requireActiveChatConfigured() {
        return requireConfigured(ModelConfigRole.CHAT);
    }

    public ModelConfig requireActiveEmbeddingConfigured() {
        return requireConfigured(ModelConfigRole.EMBEDDING);
    }

    @Deprecated
    public ModelConfig requireConfigured() {
        return requireActiveChatConfigured();
    }

    public ModelConfig requireConfigured(ModelConfigRole role) {
        ModelConfig config = activeOrDefault(role);
        if (!config.hasApiKey()) {
            throw new ModelConfigurationException(roleLabel(role) + " API Key is not configured");
        }
        return config;
    }

    @Transactional
    public ModelConfig create(ModelConfigRequest request) {
        ModelConfigRole role = normalizeRole(request.role());
        long now = System.currentTimeMillis();
        boolean active = modelConfigRepository.countByRole(role) == 0;
        return modelConfigRepository.save(mergeRequest(
                request,
                defaultConfig(role, active),
                UUID.randomUUID().toString(),
                role,
                active,
                now
        ));
    }

    @Transactional
    public ModelConfigSettingsResponse createSettings(ModelConfigUpsertRequest request) {
        ModelConfig created = create(request.toModelConfigRequest());
        return settingsSnapshot(created.role(), Optional.of(created.id()));
    }

    @Transactional
    public ModelConfig update(String id, ModelConfigRequest request) {
        ModelConfig existing = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        ModelConfigRole requestedRole = request.role() == null || request.role().isBlank()
                ? existing.role()
                : normalizeRole(request.role());
        if (requestedRole != existing.role()) {
            throw new ModelConfigurationException("Model config role cannot be changed");
        }
        return modelConfigRepository.save(mergeRequest(
                request,
                existing,
                existing.id(),
                existing.role(),
                existing.active(),
                System.currentTimeMillis()
        ));
    }

    @Transactional
    public ModelConfigSettingsResponse updateSettings(String id, ModelConfigUpsertRequest request) {
        ModelConfig updated = update(id, request.toModelConfigRequest());
        return settingsSnapshot(updated.role(), Optional.of(updated.id()));
    }

    @Transactional
    public ModelConfig activate(String id) {
        return modelConfigRepository.activate(id, System.currentTimeMillis());
    }

    @Transactional
    public ModelConfigSettingsResponse activateSettings(String id) {
        ModelConfig activated = activate(id);
        return settingsSnapshot(activated.role(), Optional.of(activated.id()));
    }

    @Transactional
    public void delete(String id) {
        ModelConfig config = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        modelConfigRepository.delete(id);
        ensureRoleHasActiveConfig(config.role());
    }

    @Transactional
    public ModelConfigSettingsResponse deleteSettings(String id) {
        ModelConfig config = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        delete(id);
        return settingsSnapshot(config.role(), Optional.empty());
    }

    private void ensureRoleHasActiveConfig(ModelConfigRole role) {
        List<ModelConfig> remaining = modelConfigRepository.findAll(role);
        if (remaining.isEmpty()) {
            // 模型设置页不能进入“无配置、无 active”的坏状态。删除某个角色最后一条配置时，
            // 立即补一条默认 active 草稿，让前端永远能拿到可显示的 selectedConfig。
            modelConfigRepository.save(defaultConfig(role, true));
            return;
        }
        if (remaining.stream().noneMatch(ModelConfig::active)) {
            modelConfigRepository.activate(remaining.getFirst().id(), System.currentTimeMillis());
        }
    }

    public ModelConfig connectionTestConfig(ModelConfigRequest request) {
        ModelConfigRole role = normalizeRole(request.role());
        ModelConfig config = mergeRequest(
                request,
                activeOrDefault(role),
                activeOrDefault(role).id(),
                role,
                true,
                System.currentTimeMillis()
        );
        if (!config.hasApiKey()) {
            throw new ModelConfigurationException(roleLabel(role) + " API Key is required for connection test");
        }
        return config;
    }

    @Transactional
    @Deprecated
    public ModelConfig save(ModelConfigRequest request) {
        long now = System.currentTimeMillis();
        ModelConfig chat = modelConfigRepository.save(mergeLegacyChatRequest(request, activeChatOrDefault(), now));
        modelConfigRepository.save(mergeLegacyEmbeddingRequest(request, activeEmbeddingOrDefault(), now));
        return chat;
    }

    private ModelConfigSettingsResponse settingsSnapshot(ModelConfigRole role, Optional<String> preferredSelectedId) {
        List<ModelConfig> configs = list(role);
        ModelConfig selected = preferredSelectedId
                .flatMap(id -> configs.stream().filter(config -> config.id().equals(id)).findFirst())
                .or(() -> configs.stream().filter(ModelConfig::active).findFirst())
                .or(() -> configs.stream().findFirst())
                .orElse(null);
        return new ModelConfigSettingsResponse(
                activeConfigsResponse(),
                role.name(),
                configs.stream().map(ModelConfigResponse::from).toList(),
                selected == null ? null : ModelConfigResponse.from(selected)
        );
    }

    private ActiveModelConfigsResponse activeConfigsResponse() {
        return new ActiveModelConfigsResponse(
                ModelConfigResponse.from(activeChatOrDefault()),
                ModelConfigResponse.from(activeEmbeddingOrDefault())
        );
    }

    private static ModelConfig mergeRequest(
            ModelConfigRequest request,
            ModelConfig existing,
            String id,
            ModelConfigRole role,
            boolean active,
            long now
    ) {
        ModelProvider provider = normalizeProvider(request.provider());
        String requestedApiKey = normalizeApiKey(request.apiKey());
        // API Key 允许留空复用旧值。否则用户只改模型 ID 时会被迫重复粘贴密钥。
        String apiKey = requestedApiKey.isBlank() ? existing.apiKey() : requestedApiKey;
        String modelName = normalizeModelName(role, request, existing.modelName());
        return new ModelConfig(
                id,
                role,
                provider,
                normalizeDisplayName(role, request.displayName()),
                normalizeBaseUrl(provider, request.baseUrl()),
                apiKey,
                modelName,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_DIMENSIONS : null,
                role == ModelConfigRole.CHAT ? normalizeTemperature(request.temperature(), existing) : null,
                role == ModelConfigRole.CHAT ? normalizeTopK(request.defaultTopK(), request.topK(), existing) : null,
                role == ModelConfigRole.CHAT ? normalizeContextWindowTokens(request.contextWindowTokens(), existing) : null,
                active,
                existing.createdAt(),
                now
        );
    }

    private static ModelConfig mergeLegacyChatRequest(ModelConfigRequest request, ModelConfig existing, long now) {
        return mergeRequest(
                new ModelConfigRequest(
                        ModelConfigRole.CHAT.name(),
                        request.provider(),
                        request.displayName(),
                        request.baseUrl(),
                        request.apiKey(),
                        request.chatModel(),
                        request.chatModel(),
                        request.embeddingModel(),
                        null,
                        request.temperature(),
                        request.topK(),
                        request.defaultTopK(),
                        request.contextWindowTokens()
                ),
                existing,
                existing.id(),
                ModelConfigRole.CHAT,
                true,
                now
        );
    }

    private static ModelConfig mergeLegacyEmbeddingRequest(ModelConfigRequest request, ModelConfig existing, long now) {
        return mergeRequest(
                new ModelConfigRequest(
                        ModelConfigRole.EMBEDDING.name(),
                        request.provider(),
                        request.displayName(),
                        request.baseUrl(),
                        request.apiKey(),
                        request.embeddingModel(),
                        request.chatModel(),
                        request.embeddingModel(),
                        request.embeddingDimensions(),
                        null,
                        null,
                        null,
                        null
                ),
                existing,
                existing.id(),
                ModelConfigRole.EMBEDDING,
                true,
                now
        );
    }

    private static ModelConfig defaultConfig(ModelConfigRole role, boolean active) {
        long now = System.currentTimeMillis();
        return new ModelConfig(
                role == ModelConfigRole.CHAT
                        ? ModelConfigDefaults.ACTIVE_CHAT_CONFIG_ID
                        : ModelConfigDefaults.ACTIVE_EMBEDDING_CONFIG_ID,
                role,
                ModelConfigDefaults.PROVIDER,
                role == ModelConfigRole.CHAT
                        ? ModelConfigDefaults.CHAT_DISPLAY_NAME
                        : ModelConfigDefaults.EMBEDDING_DISPLAY_NAME,
                ModelConfigDefaults.BASE_URL,
                "",
                role == ModelConfigRole.CHAT
                        ? ModelConfigDefaults.CHAT_MODEL
                        : ModelConfigDefaults.EMBEDDING_MODEL,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_DIMENSIONS : null,
                role == ModelConfigRole.CHAT ? ModelConfigDefaults.TEMPERATURE : null,
                role == ModelConfigRole.CHAT ? ModelConfigDefaults.TOP_K : null,
                role == ModelConfigRole.CHAT ? ModelConfigDefaults.CONTEXT_WINDOW_TOKENS : null,
                active,
                now,
                now
        );
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
                config.role(),
                config.provider(),
                config.displayName(),
                normalizedBaseUrl,
                config.apiKey(),
                config.modelName(),
                config.embeddingDimensions(),
                config.temperature(),
                config.defaultTopK(),
                config.contextWindowTokens(),
                config.active(),
                config.createdAt(),
                config.updatedAt()
        );
    }

    private static ModelConfigRole normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return ModelConfigRole.CHAT;
        }
        try {
            return ModelConfigRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ModelConfigurationException("Unsupported model config role: " + role);
        }
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

    private static String normalizeDisplayName(ModelConfigRole role, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return role == ModelConfigRole.CHAT
                    ? ModelConfigDefaults.CHAT_DISPLAY_NAME
                    : ModelConfigDefaults.EMBEDDING_DISPLAY_NAME;
        }
        return displayName.trim();
    }

    private static String normalizeBaseUrl(ModelProvider provider, String baseUrl) {
        return switch (provider) {
            case DASHSCOPE -> DashScopeBaseUrls.normalizeConfigBaseUrl(ModelConfigDefaults.BASE_URL);
            case OPENAI_COMPATIBLE -> OpenAiCompatibleUrls.normalizeBaseUrl(baseUrl);
        };
    }

    private static String normalizeApiKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    private static String normalizeModelName(ModelConfigRole role, ModelConfigRequest request, String fallback) {
        String value = request.modelName();
        if ((value == null || value.isBlank()) && role == ModelConfigRole.CHAT) {
            value = request.chatModel();
        }
        if ((value == null || value.isBlank()) && role == ModelConfigRole.EMBEDDING) {
            value = request.embeddingModel();
        }
        if (value == null || value.isBlank()) {
            value = fallback;
        }
        if (value == null || value.isBlank()) {
            throw new ModelConfigurationException(roleLabel(role) + " model name is required");
        }
        return value.trim();
    }

    private static Double normalizeTemperature(Double requested, ModelConfig existing) {
        return requested == null ? existing.resolvedTemperature() : requested;
    }

    private static Integer normalizeTopK(Integer defaultTopK, Integer topK, ModelConfig existing) {
        if (defaultTopK != null) {
            return defaultTopK;
        }
        if (topK != null) {
            return topK;
        }
        return existing.resolvedDefaultTopK();
    }

    private static Integer normalizeContextWindowTokens(Integer requested, ModelConfig existing) {
        int value = requested == null ? existing.resolvedContextWindowTokens() : requested;
        return Math.clamp(
                value,
                ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS,
                ModelConfigDefaults.MAX_CONTEXT_WINDOW_TOKENS
        );
    }

    private static String roleLabel(ModelConfigRole role) {
        return role == ModelConfigRole.CHAT ? "Chat" : "Embedding";
    }
}
