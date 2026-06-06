package com.itqianchen.agentdesign.service.ai;

import com.itqianchen.agentdesign.domain.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

final class SpringAiChatRuntime implements AiChatRuntime {

    private final String providerLabel;
    private final ChatModel chatModel;

    SpringAiChatRuntime(String providerLabel, ChatModel chatModel) {
        this.providerLabel = providerLabel;
        this.chatModel = chatModel;
    }

    @Override
    public Flux<String> stream(Prompt prompt) {
        return chatModel.stream(prompt)
                .concatMap(SpringAiChatRuntime::toTextStream);
    }

    @Override
    public Flux<String> stream(
            String systemPrompt,
            String userMessage,
            List<Advisor> advisors,
            Map<String, Object> advisorParams
    ) {
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
        return spec.stream()
                .chatResponse()
                .concatMap(SpringAiChatRuntime::toTextStream);
    }

    @Override
    public void testConnection(Prompt prompt) {
        try {
            chatModel.call(prompt);
        } catch (RuntimeException ex) {
            throw new ModelConfigurationException(providerLabel + " connection failed: " + ex.getMessage(), ex);
        }
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        // Spring AI/OpenAI-compatible 流可能发出只含元数据的结束片段。
        // 但空格和换行可能是独立 chunk，Markdown 语法依赖这些空白，不能用 isBlank 过滤。
        return response.getResult().getOutput().getText();
    }

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

    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

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
