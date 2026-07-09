package com.itqianchen.agentdesign.domain.interfaces.ai;


import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;

/**
 * 统一 Chat 模型调用接口。
 *
 * <p>业务层通过该接口接收纯文本流或同步文本，不直接依赖 Spring AI 或厂商 SDK 类型。</p>
 */
public interface AiChatRuntime {

    /**
     * 按 Prompt 启动流式生成，返回的 Flux 只包含文本 delta。
     *
     * @param prompt Spring AI Prompt，调用方负责组装系统和用户消息
     * @return 文本增量流；实现不应向外暴露厂商原始响应对象
     */
    Flux<String> stream(Prompt prompt);

    /**
     * 使用 ChatClient advisor 链启动流式生成。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @param advisors 需要参与本轮调用的 advisor 链
     * @param advisorParams 透传给 Spring AI advisor 的上下文参数，例如会话 ID
     * @return 文本增量流
     */
    Flux<String> stream(String systemPrompt, String userMessage, List<Advisor> advisors, Map<String, Object> advisorParams);

    /**
     * 使用 ChatClient advisor 链和可选工具启动流式生成。
     *
     * <p>tools/toolContext 只对本轮调用生效；未启用联网等工具能力时调用方必须传空集合，
     * 实现也不应向模型暴露任何工具 schema。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @param advisors 需要参与本轮调用的 advisor 链
     * @param advisorParams 透传给 Spring AI advisor 的上下文参数，例如会话 ID
     * @param tools 本轮允许模型调用的工具对象
     * @param toolContext 透传给工具执行器的后端上下文，不进入模型可见入参
     * @return 文本增量流
     */
    default Flux<String> stream(
            String systemPrompt,
            String userMessage,
            List<Advisor> advisors,
            Map<String, Object> advisorParams,
            List<Object> tools,
            Map<String, Object> toolContext
    ) {
        return stream(systemPrompt, userMessage, advisors, advisorParams);
    }

    /**
     * 同步单次调用，用于查询改写、路由判断等不需要 SSE 的内部流程。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return 模型返回文本；无文本内容时返回空字符串
     */
    String callText(String systemPrompt, String userMessage);

    /**
     * 同步单次多模态调用，用于 OCR 等内部流程从图片中提取文本。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户提示词
     * @param imageBytes 图片字节
     * @param mimeType 图片 MIME 类型
     * @return 模型返回文本；无文本内容时返回空字符串
     */
    String callImage(String systemPrompt, String userMessage, byte[] imageBytes, MimeType mimeType);

    /**
     * 使用最小 Prompt 验证配置可用，不保存任何业务消息。
     *
     * @param prompt 用于连通性验证的最小 Prompt
     * @throws com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException 当 Provider 调用失败时抛出
     */
    void testConnection(Prompt prompt);
}
