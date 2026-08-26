package com.itqianchen.agentdesign.domain.dto.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Model 配置 请求 定义 模型配置 接口允许接收的请求字段。
 * <p>字段校验应和前端表单、接口文档保持一致。</p>
 */
public record ModelConfigRequest(
        @Size(max = 32) String role,
        @Size(max = 32) String provider,
        @Size(max = 120) String displayName,
        @Size(max = 512) String baseUrl,
        @Size(max = 4096) String apiKey,
        @Size(max = 120) String modelName,
        @Size(max = 120) String chatModel,
        @Size(max = 120) String embeddingModel,
        @Min(1) @Max(8192) Integer embeddingDimensions,
        @Min(1) @Max(10000) Integer embeddingRequestsPerMinute,
        @Min(1000) @Max(10000000) Integer embeddingTokensPerMinute,
        @Min(1) @Max(128) Integer embeddingBatchSize,
        @Min(0) @Max(2) Double temperature,
        @Min(1) @Max(50) Integer topK,
        @Min(1) @Max(50) Integer defaultTopK,
        @Min(1024) @Max(2000000) Integer contextWindowTokens,
        @Size(max = 16) String reasoningEffort
) {
    public ModelConfigRequest(
            String role, String provider, String displayName, String baseUrl, String apiKey, String modelName,
            String chatModel, String embeddingModel, Integer embeddingDimensions, Integer embeddingRequestsPerMinute,
            Integer embeddingTokensPerMinute, Integer embeddingBatchSize, Double temperature, Integer topK,
            Integer defaultTopK, Integer contextWindowTokens
    ) {
        this(role, provider, displayName, baseUrl, apiKey, modelName, chatModel, embeddingModel,
                embeddingDimensions, embeddingRequestsPerMinute, embeddingTokensPerMinute, embeddingBatchSize,
                temperature, topK, defaultTopK, contextWindowTokens, null);
    }
}


