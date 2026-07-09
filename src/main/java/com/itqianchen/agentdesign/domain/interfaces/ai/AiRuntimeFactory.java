package com.itqianchen.agentdesign.domain.interfaces.ai;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;

/**
 * 根据用户保存的模型配置创建 Chat、Vision 或 Embedding 运行时。
 *
 * <p>实现必须把厂商 URL、模型参数和客户端缓存封装在内部，业务层只依赖本地运行时接口。</p>
 */
public interface AiRuntimeFactory {

    /**
     * 按配置创建 Chat 运行时。
     *
     * <p>Vision 模型也复用该运行时入口；实现可以缓存底层客户端，
     * 但必须在 URL、模型、密钥或生成参数变化时切换到新实例。</p>
     *
     * @param config 已归一化的模型配置
     * @return Chat 运行时
     */
    AiChatRuntime chatRuntime(ModelConfig config);

    /**
     * 按配置创建 Embedding 运行时。
     *
     * <p>Embedding 维度是索引字段契约的一部分，配置变化时不能复用旧维度的客户端。</p>
     *
     * @param config 已归一化的模型配置
     * @return Embedding 运行时
     */
    AiEmbeddingRuntime embeddingRuntime(ModelConfig config);
}
