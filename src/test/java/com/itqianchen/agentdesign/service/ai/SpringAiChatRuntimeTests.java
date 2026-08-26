package com.itqianchen.agentdesign.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatDelta;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class SpringAiChatRuntimeTests {

    /**
     * 启动 stream Preserves Whitespace Chunks For Markdown Syntax 流式流程。
     * <p>方法串联请求准备、事件流返回和结束后的状态收尾。</p>
     */
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
    void streamIgnoresReasoningOnlyChunks() {
        SpringAiChatRuntime runtime = new SpringAiChatRuntime(
                "test",
                new ReasoningChatModel()
        );

        assertThat(runtime.stream(new Prompt("test")).collectList().block())
                .containsExactly("正常回答");
        assertThat(runtime.streamDeltas(new Prompt("test")).collectList().block())
                .containsExactly(
                        new AiChatDelta(null, "用户"),
                        new AiChatDelta("正常回答", null),
                        new AiChatDelta(null, "补充推理"),
                        new AiChatDelta(null, "别名推理")
                );
    }

    /**
     * 启动 stream Fails When Provider Reports Length Finish Reason 流式流程。
     * <p>方法串联请求准备、事件流返回和结束后的状态收尾。</p>
     */
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

    private static ChatResponse reasoningChunk(String text, String reasoning) {
        return reasoningChunk(text, "reasoningContent", reasoning);
    }

    private static ChatResponse reasoningChunk(String text, String reasoningKey, String reasoning) {
        AssistantMessage message = AssistantMessage.builder()
                .content(text)
                .properties(reasoning == null ? Map.of() : Map.of(reasoningKey, reasoning))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
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

    private static final class ReasoningChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return chunk("done");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    reasoningChunk(null, "用户"),
                    reasoningChunk("正常回答", null),
                    reasoningChunk(null, "reasoning_content", "补充推理"),
                    reasoningChunk(null, "reasoning", "别名推理")
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
