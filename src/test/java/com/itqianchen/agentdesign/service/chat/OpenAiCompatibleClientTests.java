package com.itqianchen.agentdesign.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenAiCompatibleClientTests {

    @Test
    void streamPostsToCustomChatCompletionsEndpoint() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                            .body("""
                                    data: {"choices":[{"delta":{"content":"回答"}}]}

                                    data: [DONE]

                                    """)
                            .build());
                });
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                RestClient.builder(),
                webClientBuilder,
                new ObjectMapper(),
                promptProperties()
        );

        List<String> deltas = client.stream(config(), new Prompt(new UserMessage("测试问题")))
                .collectList()
                .block();

        assertThat(deltas).containsExactly("回答");
        ClientRequest request = capturedRequest.get();
        assertThat(request.method()).isEqualTo(HttpMethod.POST);
        assertThat(request.url()).hasToString("https://api.example.test/v1/chat/completions");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    }

    @Test
    void testConnectionUsesConfiguredPrompt() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                RestClient.builder()
                        .requestInterceptor((request, body, execution) -> {
                            capturedBody.set(new String(body, java.nio.charset.StandardCharsets.UTF_8));
                            return jsonResponse("{}");
                        }),
                WebClient.builder(),
                objectMapper,
                promptProperties()
        );

        client.testConnection(config());

        JsonNode body = objectMapper.readTree(capturedBody.get());
        assertThat(body.path("messages").path(0).path("content").asText())
                .isEqualTo("自定义连接测试提示词");
    }

    private static ModelConfig config() {
        return new ModelConfig(
                "active",
                ModelConfigRole.CHAT,
                ModelProvider.OPENAI_COMPATIBLE,
                "OpenAI-compatible",
                "https://api.example.test/v1",
                "sk-test",
                "gpt-4.1-mini",
                null,
                0.7,
                8,
                true,
                1L,
                2L
        );
    }

    private static ChatPromptProperties promptProperties() {
        return new ChatPromptProperties(
                new ChatPromptProperties.Rag(
                        "系统提示词",
                        "问题：{question}\n上下文：{context}",
                        "空上下文"
                ),
                new ChatPromptProperties.ConnectionTest("自定义连接测试提示词")
        );
    }

    private static MockClientHttpResponse jsonResponse(String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                HttpStatus.OK
        );
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
