package com.itqianchen.agentdesign.dto.model;

public record ActiveModelConfigsResponse(
        ModelConfigResponse chat,
        ModelConfigResponse embedding
) {
}
