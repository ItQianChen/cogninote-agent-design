package com.itqianchen.agentdesign.service.ai;

import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiEmbeddingRuntime;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import org.springframework.stereotype.Component;

/**
 * 按 ModelProvider 分派到具体厂商运行时工厂。
 *
 * <p>这是模型配置到运行时的唯一路由点，新增 provider 时应同步扩展该分派逻辑。</p>
 */
@Component
public class ModelRuntimeFactory implements AiRuntimeFactory {

    private final OpenAiCompatibleRuntimeFactory openAiCompatibleRuntimeFactory;

    /**
     * 注入所有已支持 Provider 的运行时工厂。
     *
     * @param openAiCompatibleRuntimeFactory OpenAI-compatible 运行时工厂
     */
    public ModelRuntimeFactory(OpenAiCompatibleRuntimeFactory openAiCompatibleRuntimeFactory) {
        this.openAiCompatibleRuntimeFactory = openAiCompatibleRuntimeFactory;
    }

    /**
     * 按 Provider 创建 Chat 运行时。
     *
     * @param config 已归一化的模型配置
     * @return 与 Provider 匹配的 Chat 运行时
     */
    @Override
    public AiChatRuntime chatRuntime(ModelConfig config) {
        return openAiCompatibleRuntimeFactory.chatRuntime(config);
    }

    /**
     * 按 Provider 创建 Embedding 运行时。
     *
     * @param config 已归一化的模型配置
     * @return 与 Provider 匹配的 Embedding 运行时
     */
    @Override
    public AiEmbeddingRuntime embeddingRuntime(ModelConfig config) {
        return openAiCompatibleRuntimeFactory.embeddingRuntime(config);
    }
}
