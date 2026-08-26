package com.itqianchen.agentdesign.domain.entity.model;


import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
/**
 * 用户保存的模型配置快照。
 *
 * <p>配置会同时影响聊天、向量索引和连接测试；默认值与边界归一化集中在该领域对象中维护。</p>
 */
public record ModelConfig(
        String id,
        ModelConfigRole role,
        ModelProvider provider,
        String displayName,
        String baseUrl,
        String apiKey,
        String modelName,
        Integer embeddingDimensions,
        Integer embeddingRequestsPerMinute,
        Integer embeddingTokensPerMinute,
        Integer embeddingBatchSize,
        Double temperature,
        Integer defaultTopK,
        Integer contextWindowTokens,
        boolean active,
        long createdAt,
        long updatedAt,
        String reasoningEffort
) {
    public ModelConfig(
            String id, ModelConfigRole role, ModelProvider provider, String displayName, String baseUrl,
            String apiKey, String modelName, Integer embeddingDimensions, Integer embeddingRequestsPerMinute,
            Integer embeddingTokensPerMinute, Integer embeddingBatchSize, Double temperature, Integer defaultTopK,
            Integer contextWindowTokens, boolean active, long createdAt, long updatedAt
    ) {
        this(id, role, provider, displayName, baseUrl, apiKey, modelName, embeddingDimensions,
                embeddingRequestsPerMinute, embeddingTokensPerMinute, embeddingBatchSize, temperature,
                defaultTopK, contextWindowTokens, active, createdAt, updatedAt, ModelConfigDefaults.REASONING_EFFORT);
    }

    /**
     * 返回对话模型的有效推理等级。
     *
     * <p>非 CHAT 配置没有思考语义，始终关闭，避免视觉或向量调用携带无关协议参数。</p>
     */
    public String resolvedReasoningEffort() {
        if (role != ModelConfigRole.CHAT || reasoningEffort == null || reasoningEffort.isBlank()) {
            return ModelConfigDefaults.REASONING_EFFORT;
        }
        return reasoningEffort.trim().toUpperCase(java.util.Locale.ROOT);
    }
    /**
     * 判断该配置是否包含可用于 provider 调用的 API Key。
     *
     * @return API Key 非空且非空白时为 true
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 返回 Embedding 维度的有效取值。
     *
     * <p>旧数据可能没有保存维度，统一回落到默认值可以避免索引和模型客户端使用不同向量形状。</p>
     */
    public int resolvedEmbeddingDimensions() {
        return embeddingDimensions == null
                ? ModelConfigDefaults.EMBEDDING_DIMENSIONS
                : embeddingDimensions;
    }

    /**
     * 返回 Embedding 请求 RPM 限额。
     *
     * <p>这里使用供应商控制台通用的 RPM 口径保存配置，运行时再换算为请求间隔。</p>
     */
    public int resolvedEmbeddingRequestsPerMinute() {
        return embeddingRequestsPerMinute == null
                ? ModelConfigDefaults.EMBEDDING_REQUESTS_PER_MINUTE
                : Math.clamp(
                        embeddingRequestsPerMinute,
                        ModelConfigDefaults.MIN_EMBEDDING_REQUESTS_PER_MINUTE,
                        ModelConfigDefaults.MAX_EMBEDDING_REQUESTS_PER_MINUTE
                );
    }

    /**
     * 返回 Embedding 输入 TPM 限额。
     *
     * <p>TPM 按输入文本估算，目的是减少触发供应商限流，而不是精确计费。</p>
     */
    public int resolvedEmbeddingTokensPerMinute() {
        return embeddingTokensPerMinute == null
                ? ModelConfigDefaults.EMBEDDING_TOKENS_PER_MINUTE
                : Math.clamp(
                        embeddingTokensPerMinute,
                        ModelConfigDefaults.MIN_EMBEDDING_TOKENS_PER_MINUTE,
                        ModelConfigDefaults.MAX_EMBEDDING_TOKENS_PER_MINUTE
                );
    }

    /**
     * 返回 Embedding 每批 chunk 数。
     *
     * <p>较大的 batch 可以节省 RPM，但仍受 TPM 约束；这里仅夹紧到本地安全范围。</p>
     */
    public int resolvedEmbeddingBatchSize() {
        return embeddingBatchSize == null
                ? ModelConfigDefaults.EMBEDDING_BATCH_SIZE
                : Math.clamp(
                        embeddingBatchSize,
                        ModelConfigDefaults.MIN_EMBEDDING_BATCH_SIZE,
                        ModelConfigDefaults.MAX_EMBEDDING_BATCH_SIZE
                );
    }

    /**
     * 返回生成温度的有效取值。
     *
     * <p>缺省值在领域层兜底，保证不同 provider 工厂读取到一致的生成参数。</p>
     */
    public double resolvedTemperature() {
        return temperature == null ? ModelConfigDefaults.TEMPERATURE : temperature;
    }

    /**
     * 返回默认检索 topK 的有效取值。
     *
     * <p>该值会作为新会话检索参数的种子，缺省时沿用应用默认值。</p>
     */
    public int resolvedDefaultTopK() {
        return defaultTopK == null ? ModelConfigDefaults.TOP_K : defaultTopK;
    }

    /**
     * 返回聊天上下文窗口的有效 token 上限。
     *
     * <p>非 Chat 配置没有上下文窗口语义；Chat 配置会夹紧到应用支持范围，避免异常输入撑爆上下文预算。</p>
     */
    public int resolvedContextWindowTokens() {
        if (role != ModelConfigRole.CHAT) {
            return 0;
        }
        return contextWindowTokens == null
                ? ModelConfigDefaults.CONTEXT_WINDOW_TOKENS
                : Math.clamp(
                        contextWindowTokens,
                        ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS,
                        ModelConfigDefaults.MAX_CONTEXT_WINDOW_TOKENS
                );
    }
}


