package com.itqianchen.agentdesign.domain.dto.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Model 配置 Upsert 请求 定义 模型配置 接口允许接收的请求字段。
 * <p>字段校验应和前端表单、接口文档保持一致。</p>
 */
public record ModelConfigUpsertRequest(
        @NotBlank @Size(max = 32) String role,
        @NotBlank @Size(max = 32) String provider,
        @NotBlank @Size(max = 120) String displayName,
        @Size(max = 512) String baseUrl,
        @Size(max = 4096) String apiKey,
        @NotBlank @Size(max = 120) String modelName,
        @Min(0) @Max(2) Double temperature,
        @Min(1) @Max(50) Integer defaultTopK,
        @Min(1) @Max(8192) Integer embeddingDimensions,
        @Min(1) @Max(10000) Integer embeddingRequestsPerMinute,
        @Min(1000) @Max(10000000) Integer embeddingTokensPerMinute,
        @Min(1) @Max(128) Integer embeddingBatchSize,
        @Min(1024) @Max(2000000) Integer contextWindowTokens,
        @Size(max = 16) String reasoningEffort,
        Boolean clearApiKey
) {
    public ModelConfigUpsertRequest(
            String role, String provider, String displayName, String baseUrl, String apiKey, String modelName,
            Double temperature, Integer defaultTopK, Integer embeddingDimensions, Integer embeddingRequestsPerMinute,
            Integer embeddingTokensPerMinute, Integer embeddingBatchSize, Integer contextWindowTokens,
            Boolean clearApiKey
    ) {
        this(role, provider, displayName, baseUrl, apiKey, modelName, temperature, defaultTopK,
                embeddingDimensions, embeddingRequestsPerMinute, embeddingTokensPerMinute, embeddingBatchSize,
                contextWindowTokens, null, clearApiKey);
    }
    /**
     * 转换为服务层使用的模型配置请求。
     *
     * <p>旧接口同时包含 chatModel/embeddingModel 字段，当前设置页只提交 modelName；这里负责兼容服务层契约。</p>
     *
     * @return 服务层模型配置请求
     */
    public ModelConfigRequest toModelConfigRequest() {
        String normalizedRole = role == null ? "" : role.trim().toUpperCase();
        return new ModelConfigRequest(
                role,
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                "CHAT".equals(normalizedRole) || "VISION".equals(normalizedRole) ? modelName : null,
                "EMBEDDING".equals(normalizedRole) ? modelName : null,
                embeddingDimensions,
                embeddingRequestsPerMinute,
                embeddingTokensPerMinute,
                embeddingBatchSize,
                temperature,
                defaultTopK,
                defaultTopK,
                contextWindowTokens,
                reasoningEffort
        );
    }
}
