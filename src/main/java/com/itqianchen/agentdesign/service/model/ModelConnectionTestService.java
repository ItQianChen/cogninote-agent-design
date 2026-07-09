package com.itqianchen.agentdesign.service.model;


import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.properties.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigTestResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * 模型配置保存前的连接测试服务。
 *
 * <p>Chat 模型会发起一次最小请求；Embedding 模型暂不主动请求向量接口，避免测试动作产生额外计费或维度副作用。</p>
 */
@Service
public class ModelConnectionTestService {

    private final AiRuntimeFactory aiRuntimeFactory;
    private final ChatPromptProperties promptProperties;

    /**
     * 注入模型运行时和测试提示词配置。
     *
     * @param aiRuntimeFactory AI 运行时工厂
     * @param promptProperties 提示词配置
     */
    public ModelConnectionTestService(
            AiRuntimeFactory aiRuntimeFactory,
            ChatPromptProperties promptProperties
    ) {
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.promptProperties = promptProperties;
    }

    /**
     * 按模型角色执行连接测试。
     *
     * <p>Chat 会发起最小模型请求；Embedding 只校验配置格式，避免测试动作产生向量计费。</p>
     *
     * @param config 待测试的模型配置
     * @return 测试结果
     */
    public ModelConfigTestResponse test(ModelConfig config) {
        if (config.role() == ModelConfigRole.CHAT || config.role() == ModelConfigRole.VISION) {
            /*
             * 第十一阶段统一从 AI Runtime 发起模型调用。
             * 连接测试和真实对话共享同一 Provider 运行时，避免 OpenAI-compatible/DashScope 分流再次散落。
             */
            aiRuntimeFactory.chatRuntime(config)
                    .testConnection(new Prompt(new UserMessage(promptProperties.connectionTest().user())));
            return new ModelConfigTestResponse(true, "模型连接测试成功");
        }

        // Embedding 连接会在 /models 或索引流程里验证。部分服务商没有轻量 embedding test，
        // 这里不主动发 embedding 请求，避免测试连接产生额外计费或维度副作用。
        return new ModelConfigTestResponse(
                true,
                "Embedding 配置格式已校验；未发起向量调用，请通过获取模型或重建索引验证服务端连接。"
        );
    }
}
