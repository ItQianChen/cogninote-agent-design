package com.itqianchen.agentdesign.dto.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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
        @Min(0) @Max(2) Double temperature,
        @Min(1) @Max(50) Integer topK,
        @Min(1) @Max(50) Integer defaultTopK
) {
}


