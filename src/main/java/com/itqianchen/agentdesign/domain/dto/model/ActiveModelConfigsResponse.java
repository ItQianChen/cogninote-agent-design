package com.itqianchen.agentdesign.domain.dto.model;

/**
 * Active Model Configs 响应 定义返回给前端的 模型配置 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record ActiveModelConfigsResponse(
        ModelConfigResponse chat,
        ModelConfigResponse embedding,
        ModelConfigResponse vision
) {
}
