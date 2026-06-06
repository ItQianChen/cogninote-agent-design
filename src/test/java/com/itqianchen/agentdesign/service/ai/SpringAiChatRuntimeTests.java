package com.itqianchen.agentdesign.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class SpringAiChatRuntimeTests {

    @Test
    void streamPreservesWhitespaceChunksForMarkdownSyntax() {
        SpringAiChatRuntime runtime = new SpringAiChatRuntime(
                "test",
                new StreamingWhitespaceChatModel()
        );

        String answer = String.join("", runtime.stream(new Prompt("test")).collectList().block());

        assertThat(answer).isEqualTo("### 二、标题\n- 列表项");
    }

    @Test
    void streamFailsWhenProviderReportsLengthFinishReason() {
        SpringAiChatRuntime runtime = new SpringAiChatRuntime(
                "test",
                new TruncatedChatModel()
        );

        assertThatThrownBy(() -> runtime.stream(new Prompt("test")).collectList().block())
                .isInstanceOf(ChatCompletionIncompleteException.class)
                .hasMessageContaining("finishReason=length");
    }

    @Test
    void chatClientStreamFailsWhenProviderReportsLengthFinishReason() {
        SpringAiChatRuntime runtime = new SpringAiChatRuntime(
                "test",
                new TruncatedChatModel()
        );

        assertThatThrownBy(() -> runtime.stream(
                        "system",
                        "user",
                        List.of(),
                        null
                ).collectList().block())
                .isInstanceOf(ChatCompletionIncompleteException.class)
                .hasMessageContaining("finishReason=length");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse finishedChunk(String text, String finishReason) {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason(finishReason).build()
        )));
    }

    private static final class StreamingWhitespaceChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return chunk("done");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    chunk("###"),
                    chunk(" "),
                    chunk("二、标题"),
                    chunk("\n"),
                    chunk("-"),
                    chunk(" "),
                    chunk("列表项")
            );
        }
    }

    private static final class TruncatedChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return finishedChunk("partial", "length");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    chunk("回答前半段"),
                    finishedChunk("", "length")
            );
        }
    }
}
