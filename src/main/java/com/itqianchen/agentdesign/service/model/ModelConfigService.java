package com.itqianchen.agentdesign.service.model;


import com.itqianchen.agentdesign.domain.dto.model.ActiveModelConfigsResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigSettingsResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigUpsertRequest;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理 Chat、Embedding 与 Vision 三类模型配置。
 *
 * <p>每个角色都必须始终有一个 active 配置。API Key 留空表示复用旧密钥，
 * 这样用户修改模型名或 Base URL 时不会被迫重新输入敏感信息。</p>
 */
@Service
public class ModelConfigService {

    private final ModelConfigRepository modelConfigRepository;

    /**
     * 注入模型配置仓储。
     *
     * @param modelConfigRepository 模型配置仓储
     */
    public ModelConfigService(ModelConfigRepository modelConfigRepository) {
        this.modelConfigRepository = modelConfigRepository;
    }

    /**
     * 查询指定角色的模型配置列表。
     *
     * @param role 模型角色
     * @return 已归一化的配置列表
     */
    public List<ModelConfig> list(ModelConfigRole role) {
        return modelConfigRepository.findAll(role).stream()
                .map(ModelConfigService::normalizeLoadedConfig)
                .toList();
    }

    /**
     * 构建模型设置页使用的配置快照。
     * <p>快照同时包含列表、选中项和当前激活项，便于前端一次性渲染。</p>
     *
     * @param role 当前设置页角色
     * @return 设置页快照
     */
    @Transactional
    public ModelConfigSettingsResponse settingsSnapshot(ModelConfigRole role) {
        ensureRoleHasActiveConfig(ModelConfigRole.CHAT);
        ensureRoleHasActiveConfig(ModelConfigRole.EMBEDDING);
        ensureRoleHasActiveConfig(ModelConfigRole.VISION);
        return settingsSnapshot(role, Optional.empty());
    }

    /**
     * 读取激活的 Chat 配置；缺失时返回默认草稿。
     *
     * @return Chat 配置
     */
    public ModelConfig activeChatOrDefault() {
        return activeOrDefault(ModelConfigRole.CHAT);
    }

    /**
     * 读取激活的 Embedding 配置；缺失时返回默认草稿。
     *
     * @return Embedding 配置
     */
    public ModelConfig activeEmbeddingOrDefault() {
        return activeOrDefault(ModelConfigRole.EMBEDDING);
    }

    /**
     * 读取激活的 Vision 配置；缺失时返回默认草稿。
     *
     * @return Vision 配置
     */
    public ModelConfig activeVisionOrDefault() {
        return activeOrDefault(ModelConfigRole.VISION);
    }

    /**
     * 读取指定角色激活配置；缺失时返回默认草稿。
     *
     * <p>返回默认草稿不代表已持久化，运行时调用仍需 requireConfigured 检查 API Key。</p>
     *
     * @param role 模型角色
     * @return 激活配置或默认草稿
     */
    public ModelConfig activeOrDefault(ModelConfigRole role) {
        return modelConfigRepository.findActive(role)
                .map(ModelConfigService::normalizeLoadedConfig)
                .orElseGet(() -> defaultConfig(role, true));
    }

    /**
     * 旧版入口，固定读取 Chat 激活配置。
     *
     * @return Chat 配置
     */
    @Deprecated
    public ModelConfig activeOrDefault() {
        return activeChatOrDefault();
    }

    /**
     * 读取已配置 API Key 的 Chat 配置。
     *
     * @return 可用于模型调用的 Chat 配置
     */
    public ModelConfig requireActiveChatConfigured() {
        return requireConfigured(ModelConfigRole.CHAT);
    }

    /**
     * 读取已配置 API Key 的 Embedding 配置。
     *
     * @return 可用于向量生成的 Embedding 配置
     */
    public ModelConfig requireActiveEmbeddingConfigured() {
        return requireConfigured(ModelConfigRole.EMBEDDING);
    }

    /**
     * 读取已配置 API Key 的 Vision 配置。
     *
     * @return 可用于多模态图片识别的 Vision 配置
     */
    public ModelConfig requireActiveVisionConfigured() {
        return requireConfigured(ModelConfigRole.VISION);
    }

    /**
     * 旧版入口，固定检查 Chat 配置。
     *
     * @return 可用于模型调用的 Chat 配置
     */
    @Deprecated
    public ModelConfig requireConfigured() {
        return requireActiveChatConfigured();
    }

    /**
     * 读取指定角色且已配置 API Key 的配置。
     *
     * <p>运行时调用外部模型前必须走这个入口，避免把默认草稿当成真实配置。</p>
     *
     * @param role 模型角色
     * @return 可用于调用的配置
     * @throws ModelConfigurationException 当 API Key 为空时抛出
     */
    public ModelConfig requireConfigured(ModelConfigRole role) {
        ModelConfig config = activeOrDefault(role);
        if (!config.hasApiKey()) {
            throw new ModelConfigurationException(roleLabel(role) + " API Key is not configured");
        }
        return config;
    }

    /**
     * 创建模型配置。
     *
     * <p>同角色第一条配置会自动激活，确保新安装或清空后仍能得到明确的 active 配置。</p>
     *
     * @param request 模型配置请求
     * @return 新建配置
     */
    @Transactional
    public ModelConfig create(ModelConfigRequest request) {
        ModelConfigRole role = normalizeRole(request.role());
        long now = System.currentTimeMillis();
        // 每个角色的第一条配置自动激活，保证模型设置页和运行时都有明确选择。
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

    /**
     * 设置页创建模型配置并返回刷新后的快照。
     *
     * @param request 设置页提交的配置
     * @return 设置页快照
     */
    @Transactional
    public ModelConfigSettingsResponse createSettings(ModelConfigUpsertRequest request) {
        ModelConfig created = create(request.toModelConfigRequest());
        return settingsSnapshot(created.role(), Optional.of(created.id()));
    }

    /**
     * 更新已有模型配置。
     *
     * <p>role 是配置归属边界，创建后不允许修改，否则历史 active 约束和运行时缓存会混乱。</p>
     *
     * @param id 配置 ID
     * @param request 更新请求
     * @return 更新后的配置
     */
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

    /**
     * 设置页更新模型配置并返回刷新后的快照。
     *
     * @param id 配置 ID
     * @param request 设置页提交的配置
     * @return 设置页快照
     */
    @Transactional
    public ModelConfigSettingsResponse updateSettings(String id, ModelConfigUpsertRequest request) {
        ModelConfig updated = updateSettingsConfig(id, request);
        return settingsSnapshot(updated.role(), Optional.of(updated.id()));
    }

    private ModelConfig updateSettingsConfig(String id, ModelConfigUpsertRequest request) {
        ModelConfig existing = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        ModelConfigRole requestedRole = request.role() == null || request.role().isBlank()
                ? existing.role()
                : normalizeRole(request.role());
        if (requestedRole != existing.role()) {
            throw new ModelConfigurationException("Model config role cannot be changed");
        }
        return modelConfigRepository.save(mergeRequest(
                request.toModelConfigRequest(),
                existing,
                existing.id(),
                existing.role(),
                existing.active(),
                System.currentTimeMillis(),
                Boolean.TRUE.equals(request.clearApiKey())
        ));
    }

    /**
     * 激活指定模型配置。
     *
     * @param id 配置 ID
     * @return 激活后的配置
     */
    @Transactional
    public ModelConfig activate(String id) {
        return modelConfigRepository.activate(id, System.currentTimeMillis());
    }

    /**
     * 设置页激活配置并返回刷新后的快照。
     *
     * @param id 配置 ID
     * @return 设置页快照
     */
    @Transactional
    public ModelConfigSettingsResponse activateSettings(String id) {
        ModelConfig activated = activate(id);
        return settingsSnapshot(activated.role(), Optional.of(activated.id()));
    }

    /**
     * 删除模型配置。
     *
     * <p>删除后会立即保证同角色仍有 active 配置，避免运行时读取到无主状态。</p>
     *
     * @param id 配置 ID
     */
    @Transactional
    public void delete(String id) {
        ModelConfig config = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        modelConfigRepository.delete(id);
        ensureRoleHasActiveConfig(config.role());
    }

    /**
     * 设置页删除配置并返回刷新后的快照。
     *
     * @param id 配置 ID
     * @return 设置页快照
     */
    @Transactional
    public ModelConfigSettingsResponse deleteSettings(String id) {
        ModelConfig config = modelConfigRepository.findById(id)
                .orElseThrow(() -> new ModelConfigurationException("Model config not found: " + id));
        delete(id);
        return settingsSnapshot(config.role(), Optional.empty());
    }

    /**
     * 确保指定角色存在 active 配置。
     *
     * <p>这是删除和设置页快照的兜底约束，防止用户操作后进入“有角色但无选中配置”的坏状态。</p>
     *
     * @param role 模型角色
     */
    private void ensureRoleHasActiveConfig(ModelConfigRole role) {
        List<ModelConfig> remaining = modelConfigRepository.findAll(role);
        if (remaining.isEmpty()) {
            // 模型设置页不能进入“无配置、无 active”的坏状态。删除某个角色最后一条配置时，
            // 立即补一条默认 active 草稿，让前端永远能拿到可显示的 selectedConfig。
            modelConfigRepository.save(defaultConfig(role, true));
            return;
        }
        if (remaining.stream().noneMatch(ModelConfig::active)) {
            // 删除 active 配置后选择同角色第一条作为新的 active，避免运行时拿到无主配置。
            modelConfigRepository.activate(remaining.getFirst().id(), System.currentTimeMillis());
        }
    }

    /**
     * 构建连接测试使用的临时配置。
     *
     * <p>临时配置会合并当前 active 配置中的旧 API Key，但不会写入 SQLite。</p>
     *
     * @param request 临时配置请求
     * @return 可用于连通性测试的配置
     */
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

    /**
     * 旧版保存接口，一次性保存 Chat 和 Embedding 配置。
     *
     * <p>保留该方法是为了兼容旧前端和历史测试；新设置页应调用按角色拆分的 create/update 接口。</p>
     *
     * @param request 旧版配置请求
     * @return 保存后的 Chat 配置
     */
    @Transactional
    @Deprecated
    public ModelConfig save(ModelConfigRequest request) {
        long now = System.currentTimeMillis();
        // 旧设置接口一次保存 Chat 和 Embedding；保留该路径用于兼容旧前端或历史测试。
        ModelConfig chat = modelConfigRepository.save(mergeLegacyChatRequest(request, activeChatOrDefault(), now));
        modelConfigRepository.save(mergeLegacyEmbeddingRequest(request, activeEmbeddingOrDefault(), now));
        return chat;
    }

    /**
     * 构建模型设置页使用的配置快照。
     * <p>快照同时包含列表、选中项和当前激活项，便于前端一次性渲染。</p>
     *
     * @param role 当前设置页角色
     * @param preferredSelectedId 优先选中的配置 ID
     * @return 设置页快照
     */
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

    /**
     * 构建当前激活配置响应。
     *
     * @return Chat、Embedding 与 Vision 的激活配置响应
     */
    private ActiveModelConfigsResponse activeConfigsResponse() {
        return new ActiveModelConfigsResponse(
                ModelConfigResponse.from(activeChatOrDefault()),
                ModelConfigResponse.from(activeEmbeddingOrDefault()),
                ModelConfigResponse.from(activeVisionOrDefault())
        );
    }

    /**
     * 将请求合并到已有配置。
     *
     * <p>API Key 留空表示沿用旧值；模型名、Base URL 和上下文窗口会在这里统一归一化。</p>
     *
     * @param request 请求配置
     * @param existing 现有配置或默认草稿
     * @param id 目标配置 ID
     * @param role 模型角色
     * @param active 是否激活
     * @param now 更新时间戳
     * @return 合并后的配置
     */
    private static ModelConfig mergeRequest(
            ModelConfigRequest request,
            ModelConfig existing,
            String id,
            ModelConfigRole role,
            boolean active,
            long now
    ) {
        return mergeRequest(request, existing, id, role, active, now, false);
    }

    private static ModelConfig mergeRequest(
            ModelConfigRequest request,
            ModelConfig existing,
            String id,
            ModelConfigRole role,
            boolean active,
            long now,
            boolean clearApiKey
    ) {
        ModelProvider provider = normalizeProvider(request.provider());
        String requestedApiKey = normalizeApiKey(request.apiKey());
        // 旧接口仍把空 Key 视为沿用；设置页通过 clearApiKey 显式表达“删除本机密钥”。
        String apiKey = clearApiKey ? "" : requestedApiKey.isBlank() ? existing.apiKey() : requestedApiKey;
        String modelName = normalizeModelName(role, request, existing.modelName());
        return new ModelConfig(
                id,
                role,
                provider,
                normalizeDisplayName(role, request.displayName()),
                normalizeBaseUrl(request.baseUrl()),
                apiKey,
                modelName,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_DIMENSIONS : null,
                role == ModelConfigRole.EMBEDDING
                        ? normalizeEmbeddingRequestsPerMinute(request.embeddingRequestsPerMinute(), existing)
                        : null,
                role == ModelConfigRole.EMBEDDING
                        ? normalizeEmbeddingTokensPerMinute(request.embeddingTokensPerMinute(), existing)
                        : null,
                role == ModelConfigRole.EMBEDDING
                        ? normalizeEmbeddingBatchSize(request.embeddingBatchSize(), existing)
                        : null,
                isGenerationRole(role) ? normalizeTemperature(request.temperature(), existing, role) : null,
                role == ModelConfigRole.CHAT ? normalizeTopK(request.defaultTopK(), request.topK(), existing) : null,
                role == ModelConfigRole.CHAT ? normalizeContextWindowTokens(request.contextWindowTokens(), existing) : null,
                active,
                existing.createdAt(),
                now,
                normalizeReasoningEffort(request.reasoningEffort(), existing, role)
        );
    }

    /**
     * 将旧版请求拆成 Chat 配置。
     *
     * @param request 旧版请求
     * @param existing 现有 Chat 配置
     * @param now 更新时间戳
     * @return Chat 配置
     */
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
                        null,
                        null,
                        null,
                        request.temperature(),
                        request.topK(),
                        request.defaultTopK(),
                        request.contextWindowTokens(),
                        request.reasoningEffort()
                ),
                existing,
                existing.id(),
                ModelConfigRole.CHAT,
                true,
                now
        );
    }

    /**
     * 将旧版请求拆成 Embedding 配置。
     *
     * @param request 旧版请求
     * @param existing 现有 Embedding 配置
     * @param now 更新时间戳
     * @return Embedding 配置
     */
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
                        request.embeddingRequestsPerMinute(),
                        request.embeddingTokensPerMinute(),
                        request.embeddingBatchSize(),
                        null,
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

    /**
     * 构建指定角色的默认配置草稿。
     *
     * @param role 模型角色
     * @param active 是否作为默认 active 配置
     * @return 默认配置
     */
    private static ModelConfig defaultConfig(ModelConfigRole role, boolean active) {
        long now = System.currentTimeMillis();
        String id;
        String displayName;
        String modelName;
        Double temperature;
        if (role == ModelConfigRole.CHAT) {
            id = ModelConfigDefaults.ACTIVE_CHAT_CONFIG_ID;
            displayName = ModelConfigDefaults.CHAT_DISPLAY_NAME;
            modelName = ModelConfigDefaults.CHAT_MODEL;
            temperature = ModelConfigDefaults.TEMPERATURE;
        } else if (role == ModelConfigRole.EMBEDDING) {
            id = ModelConfigDefaults.ACTIVE_EMBEDDING_CONFIG_ID;
            displayName = ModelConfigDefaults.EMBEDDING_DISPLAY_NAME;
            modelName = ModelConfigDefaults.EMBEDDING_MODEL;
            temperature = null;
        } else {
            id = ModelConfigDefaults.ACTIVE_VISION_CONFIG_ID;
            displayName = ModelConfigDefaults.VISION_DISPLAY_NAME;
            modelName = ModelConfigDefaults.VISION_MODEL;
            temperature = ModelConfigDefaults.VISION_TEMPERATURE;
        }
        return new ModelConfig(
                id,
                role,
                ModelConfigDefaults.PROVIDER,
                displayName,
                ModelConfigDefaults.BASE_URL,
                "",
                modelName,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_DIMENSIONS : null,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_REQUESTS_PER_MINUTE : null,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_TOKENS_PER_MINUTE : null,
                role == ModelConfigRole.EMBEDDING ? ModelConfigDefaults.EMBEDDING_BATCH_SIZE : null,
                temperature,
                role == ModelConfigRole.CHAT ? ModelConfigDefaults.TOP_K : null,
                role == ModelConfigRole.CHAT ? ModelConfigDefaults.CONTEXT_WINDOW_TOKENS : null,
                active,
                now,
                now,
                ModelConfigDefaults.REASONING_EFFORT
        );
    }

    /**
     * 读取后归一化 OpenAI-compatible Base URL 和推理等级。
     *
     * @param config 数据库中的配置
     * @return 归一化后的配置
     */
    private static ModelConfig normalizeLoadedConfig(ModelConfig config) {
        String normalizedBaseUrl = OpenAiCompatibleUrls.normalizeBaseUrl(config.baseUrl());
        String reasoningEffort = normalizeReasoningEffort(config.reasoningEffort(), config, config.role());
        if (normalizedBaseUrl.equals(config.baseUrl()) && reasoningEffort.equals(config.reasoningEffort())) {
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
                config.embeddingRequestsPerMinute(),
                config.embeddingTokensPerMinute(),
                config.embeddingBatchSize(),
                config.temperature(),
                config.defaultTopK(),
                config.contextWindowTokens(),
                config.active(),
                config.createdAt(),
                config.updatedAt(),
                reasoningEffort
        );
    }

    /**
     * 归一化模型角色。
     *
     * @param role 请求中的角色字符串
     * @return 模型角色；空值兼容为 CHAT
     */
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

    /**
     * 归一化模型 Provider。
     *
     * @param provider 请求中的 Provider 字符串
     * @return 模型 Provider；空值使用默认 Provider
     */
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

    /**
     * 归一化展示名称。
     *
     * @param role 模型角色
     * @param displayName 请求展示名称
     * @return 展示名称
     */
    private static String normalizeDisplayName(ModelConfigRole role, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return role == ModelConfigRole.CHAT
                    ? ModelConfigDefaults.CHAT_DISPLAY_NAME
                    : role == ModelConfigRole.EMBEDDING
                            ? ModelConfigDefaults.EMBEDDING_DISPLAY_NAME
                            : ModelConfigDefaults.VISION_DISPLAY_NAME;
        }
        return displayName.trim();
    }

    /**
     * 归一化 OpenAI-compatible Base URL。
     * @param baseUrl 请求 Base URL
     * @return 可用于客户端构造的 Base URL
     */
    private static String normalizeBaseUrl(String baseUrl) {
        return OpenAiCompatibleUrls.normalizeBaseUrl(baseUrl);
    }

    /**
     * 归一化通用推理等级。
     *
     * <p>只有 Chat 调用能消费该协议字段；其他角色强制关闭，避免保存无效配置。</p>
     */
    private static String normalizeReasoningEffort(String requested, ModelConfig existing, ModelConfigRole role) {
        if (role != ModelConfigRole.CHAT) {
            return ModelConfigDefaults.REASONING_EFFORT;
        }
        String value = requested == null || requested.isBlank()
                ? existing == null ? ModelConfigDefaults.REASONING_EFFORT : existing.resolvedReasoningEffort()
                : requested.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (value) {
            case "NONE", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX" -> value;
            default -> throw new ModelConfigurationException("Unsupported reasoning effort: " + requested);
        };
    }

    /**
     * 归一化 API Key。
     *
     * @param apiKey 请求 API Key
     * @return 去除首尾空白后的 Key；空值返回空字符串
     */
    private static String normalizeApiKey(String apiKey) {
        return apiKey == null ? "" : apiKey.trim();
    }

    /**
     * 归一化模型名称。
     *
     * <p>兼容旧请求里的 chatModel/embeddingModel 字段，新接口优先使用 modelName。</p>
     *
     * @param role 模型角色
     * @param request 请求配置
     * @param fallback 现有配置中的模型名
     * @return 非空模型名称
     */
    private static String normalizeModelName(ModelConfigRole role, ModelConfigRequest request, String fallback) {
        String value = request.modelName();
        if ((value == null || value.isBlank()) && isGenerationRole(role)) {
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

    /**
     * 归一化采样温度。
     *
     * @param requested 请求值
     * @param existing 现有配置
     * @return 请求值或现有解析值
     */
    private static Double normalizeTemperature(Double requested, ModelConfig existing, ModelConfigRole role) {
        if (requested != null) {
            return requested;
        }
        if (existing.temperature() != null) {
            return existing.temperature();
        }
        return role == ModelConfigRole.VISION
                ? ModelConfigDefaults.VISION_TEMPERATURE
                : existing.resolvedTemperature();
    }

    /**
     * 归一化默认检索数量。
     *
     * <p>兼容旧字段 topK，新字段 defaultTopK 优先。</p>
     *
     * @param defaultTopK 新字段值
     * @param topK 旧字段值
     * @param existing 现有配置
     * @return 默认检索数量
     */
    private static Integer normalizeTopK(Integer defaultTopK, Integer topK, ModelConfig existing) {
        if (defaultTopK != null) {
            return defaultTopK;
        }
        if (topK != null) {
            return topK;
        }
        return existing.resolvedDefaultTopK();
    }

    /**
     * 归一化 Embedding 请求 RPM。
     *
     * @param requested 请求值
     * @param existing 现有配置
     * @return 夹紧后的每分钟请求数
     */
    private static Integer normalizeEmbeddingRequestsPerMinute(Integer requested, ModelConfig existing) {
        int value = requested == null ? existing.resolvedEmbeddingRequestsPerMinute() : requested;
        return Math.clamp(
                value,
                ModelConfigDefaults.MIN_EMBEDDING_REQUESTS_PER_MINUTE,
                ModelConfigDefaults.MAX_EMBEDDING_REQUESTS_PER_MINUTE
        );
    }

    /**
     * 归一化 Embedding 输入 TPM。
     *
     * @param requested 请求值
     * @param existing 现有配置
     * @return 夹紧后的每分钟输入 token 数
     */
    private static Integer normalizeEmbeddingTokensPerMinute(Integer requested, ModelConfig existing) {
        int value = requested == null ? existing.resolvedEmbeddingTokensPerMinute() : requested;
        return Math.clamp(
                value,
                ModelConfigDefaults.MIN_EMBEDDING_TOKENS_PER_MINUTE,
                ModelConfigDefaults.MAX_EMBEDDING_TOKENS_PER_MINUTE
        );
    }

    /**
     * 归一化 Embedding 批量大小。
     *
     * @param requested 请求值
     * @param existing 现有配置
     * @return 夹紧后的每批 chunk 数
     */
    private static Integer normalizeEmbeddingBatchSize(Integer requested, ModelConfig existing) {
        int value = requested == null ? existing.resolvedEmbeddingBatchSize() : requested;
        return Math.clamp(
                value,
                ModelConfigDefaults.MIN_EMBEDDING_BATCH_SIZE,
                ModelConfigDefaults.MAX_EMBEDDING_BATCH_SIZE
        );
    }

    /**
     * 归一化上下文窗口大小。
     *
     * @param requested 请求值
     * @param existing 现有配置
     * @return 限制在项目允许范围内的 token 数
     */
    private static Integer normalizeContextWindowTokens(Integer requested, ModelConfig existing) {
        int value = requested == null ? existing.resolvedContextWindowTokens() : requested;
        return Math.clamp(
                value,
                ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS,
                ModelConfigDefaults.MAX_CONTEXT_WINDOW_TOKENS
        );
    }

    /**
     * 转换角色标签用于错误提示。
     *
     * @param role 模型角色
     * @return 面向用户的角色名称
     */
    private static String roleLabel(ModelConfigRole role) {
        return switch (role) {
            case CHAT -> "Chat";
            case EMBEDDING -> "Embedding";
            case VISION -> "Vision";
        };
    }

    private static boolean isGenerationRole(ModelConfigRole role) {
        return role == ModelConfigRole.CHAT || role == ModelConfigRole.VISION;
    }
}
