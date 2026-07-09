package com.itqianchen.agentdesign.domain.dto.model;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;

/**
 * Model 配置 响应 定义返回给前端的 模型配置 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record ModelConfigResponse(
        String id,
        String role,
        String provider,
        String displayName,
        String baseUrl,
        boolean apiKeyConfigured,
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
        Long createdAt,
        Long updatedAt
) {
    /**
     * 构造模型设置页的配置响应。
     *
     * <p>contextWindowTokens 只对 Chat 配置有意义，非 Chat 响应保持 null 以免前端误展示。</p>
     */
    public static ModelConfigResponse from(ModelConfig config) {
        return new ModelConfigResponse(
                config.id(),
                config.role().name(),
                config.provider().name(),
                config.displayName(),
                config.baseUrl(),
                config.hasApiKey(),
                config.apiKey(),
                config.modelName(),
                config.embeddingDimensions(),
                config.role() == ModelConfigRole.EMBEDDING
                        ? config.resolvedEmbeddingRequestsPerMinute()
                        : null,
                config.role() == ModelConfigRole.EMBEDDING
                        ? config.resolvedEmbeddingTokensPerMinute()
                        : null,
                config.role() == ModelConfigRole.EMBEDDING
                        ? config.resolvedEmbeddingBatchSize()
                        : null,
                config.temperature(),
                config.defaultTopK(),
                config.role() == ModelConfigRole.CHAT
                        ? config.resolvedContextWindowTokens()
                        : null,
                config.active(),
                config.createdAt(),
                config.updatedAt()
        );
    }
}


