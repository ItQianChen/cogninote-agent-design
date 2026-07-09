package com.itqianchen.agentdesign.service.ai;


import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

/**
 * 基于 Spring AI ChatModel 的本地 Chat 运行时实现。
 *
 * <p>这里把 ChatResponse 转为纯文本 Flux，并把 finishReason=length 等截断信号提升为业务异常。</p>
 */
final class SpringAiChatRuntime implements AiChatRuntime {

    private final String providerLabel;
    private final ChatModel chatModel;

    /**
     * 绑定 Provider 标签和 Spring AI ChatModel。
     *
     * @param providerLabel 用于错误消息的 Provider 名称
     * @param chatModel 已按用户配置构造的 ChatModel
     */
    SpringAiChatRuntime(String providerLabel, ChatModel chatModel) {
        this.providerLabel = providerLabel;
        this.chatModel = chatModel;
    }

    /**
     * 直接按 Prompt 进行流式生成。
     *
     * <p>返回值只包含文本片段，截断完成原因会在流中转换为 ChatCompletionIncompleteException。</p>
     *
     * @param prompt Spring AI Prompt
     * @return 文本增量流
     */
    @Override
    public Flux<String> stream(Prompt prompt) {
        return chatModel.stream(prompt)
                .concatMap(SpringAiChatRuntime::toTextStream);
    }

    /**
     * 通过 ChatClient 运行带 advisor 的流式生成。
     *
     * <p>记忆和 RAG 依赖 advisor 参数传递，因此该路径不能退化成直接调用 ChatModel。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @param advisors 本轮需要启用的 advisor
     * @param advisorParams advisor 上下文参数
     * @return 文本增量流
     */
    @Override
    public Flux<String> stream(
            String systemPrompt,
            String userMessage,
            List<Advisor> advisors,
            Map<String, Object> advisorParams
    ) {
        return stream(systemPrompt, userMessage, advisors, advisorParams, List.of(), Map.of());
    }

    /**
     * 通过 ChatClient 运行带 advisor 和工具的流式生成。
     *
     * <p>Spring AI 的 tools(...) 是 varargs；传入 List 会被识别成一个普通工具对象，
     * 因此这里必须展开为 Object[]。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @param advisors 本轮需要启用的 advisor
     * @param advisorParams advisor 上下文参数
     * @param tools 本轮允许模型调用的工具对象
     * @param toolContext 工具执行期后端上下文
     * @return 文本增量流
     */
    @Override
    public Flux<String> stream(
            String systemPrompt,
            String userMessage,
            List<Advisor> advisors,
            Map<String, Object> advisorParams,
            List<Object> tools,
            Map<String, Object> toolContext
    ) {
        // ChatClient 才支持 advisors；直接调用 ChatModel 会丢失记忆和 RAG advisor 参数。
        ChatClient.ChatClientRequestSpec spec = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(systemPrompt)
                .user(userMessage);
        if ((advisors != null && !advisors.isEmpty()) || (advisorParams != null && !advisorParams.isEmpty())) {
            spec = spec.advisors(advisor -> {
                if (advisors != null && !advisors.isEmpty()) {
                    advisor.advisors(advisors);
                }
                if (advisorParams != null && !advisorParams.isEmpty()) {
                    advisor.params(advisorParams);
                }
            });
        }
        if (tools != null && !tools.isEmpty()) {
            spec = spec.tools(tools.toArray());
        }
        if (toolContext != null && !toolContext.isEmpty()) {
            spec = spec.toolContext(toolContext);
        }
        return spec.stream()
                .chatResponse()
                .concatMap(SpringAiChatRuntime::toTextStream);
    }

    /**
     * 同步获取模型文本结果。
     *
     * <p>用于内部决策链路，返回空字符串代表 Provider 没有给出可展示文本，不代表调用失败。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户消息
     * @return 模型文本或空字符串
     */
    @Override
    public String callText(String systemPrompt, String userMessage) {
        ChatResponse response = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .chatResponse();
        String text = extractText(response);
        return text == null ? "" : text;
    }

    /**
     * 同步获取模型对图片的文本输出。
     *
     * <p>该路径用于 PDF OCR，直接使用 ChatModel + Media，避免把图片识别混入带记忆和工具的聊天链路。</p>
     *
     * @param systemPrompt 系统提示词
     * @param userMessage 用户提示词
     * @param imageBytes 图片字节
     * @param mimeType 图片 MIME 类型
     * @return 模型文本或空字符串
     */
    @Override
    public String callImage(String systemPrompt, String userMessage, byte[] imageBytes, MimeType mimeType) {
        try {
            UserMessage message = UserMessage.builder()
                    .text(userMessage)
                    .media(Media.builder()
                            .mimeType(mimeType)
                            .data(imageBytes)
                            .build())
                    .build();
            ChatResponse response = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    message
            )));
            String text = extractText(response);
            return text == null ? "" : text;
        } catch (RuntimeException ex) {
            throw new ModelConfigurationException(providerLabel + " vision call failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 执行最小调用验证模型配置。
     *
     * <p>底层 RuntimeException 会包装为 ModelConfigurationException，统一返回给设置页。</p>
     *
     * @param prompt 测试 Prompt
     */
    @Override
    public void testConnection(Prompt prompt) {
        try {
            chatModel.call(prompt);
        } catch (RuntimeException ex) {
            throw new ModelConfigurationException(providerLabel + " connection failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * 从 Spring AI 响应中提取文本。
     *
     * @param response Spring AI 响应，可能为空或只包含元数据
     * @return 文本内容；没有文本时返回 null
     */
    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        // Spring AI/OpenAI-compatible 流可能发出只含元数据的结束片段。
        // 但空格和换行可能是独立 chunk，Markdown 语法依赖这些空白，不能用 isBlank 过滤。
        return response.getResult().getOutput().getText();
    }

    /**
     * 将单个 ChatResponse 转换为文本 Flux。
     *
     * <p>截断信号必须在这里抛出，否则上层 SSE 无法区分“正常结束”和“达到输出上限”。</p>
     *
     * @param response Spring AI 响应
     * @return 文本片段或截断错误
     */
    private static Flux<String> toTextStream(ChatResponse response) {
        String text = extractText(response);
        String finishReason = finishReason(response);
        if (!isIncompleteFinishReason(finishReason)) {
            return text == null || text.isEmpty() ? Flux.empty() : Flux.just(text);
        }

        ChatCompletionIncompleteException exception = new ChatCompletionIncompleteException(
                "模型回答被提前截断，finishReason=" + finishReason + "。请提高模型输出长度上限，或让模型继续回答。"
        );
        if (text == null || text.isEmpty()) {
            return Flux.error(exception);
        }
        // 某些 Provider 会把最后一点内容和截断原因放在同一个 chunk。
        // 先交付已收到的文字，再把本轮流标记为未完成，避免吞掉最后一段。
        return Flux.just(text).concatWith(Flux.error(exception));
    }

    /**
     * 读取 Provider 返回的完成原因。
     *
     * @param response Spring AI 响应
     * @return finishReason；没有元数据时返回 null
     */
    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /**
     * 判断完成原因是否代表回答不完整。
     *
     * <p>不同 Provider 对输出上限的字段命名不一致，这里统一归一为同一类业务错误。</p>
     *
     * @param finishReason Provider 返回的完成原因
     * @return 是否应向调用方报告回答被截断
     */
    private static boolean isIncompleteFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String normalized = finishReason.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("length")
                || normalized.equals("max_tokens")
                || normalized.equals("max_completion_tokens")
                || normalized.equals("content_filter");
    }
}
