package com.itqianchen.agentdesign.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.service.model.OpenAiCompatibleUrls;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
public class OpenAiCompatibleClient {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ChatPromptProperties promptProperties;

    public OpenAiCompatibleClient(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ChatPromptProperties promptProperties
    ) {
        this.restClient = restClientBuilder.build();
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.promptProperties = promptProperties;
    }

    public Flux<String> stream(ModelConfig config, Prompt prompt) {
        return webClientBuilder.build()
                .post()
                .uri(OpenAiCompatibleUrls.chatCompletionsUri(config.baseUrl()))
                .headers(headers -> headers.setBearerAuth(config.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(chatRequest(config, prompt, true))
                .retrieve()
                .bodyToFlux(SSE_TYPE)
                .map(ServerSentEvent::data)
                .takeUntil(data -> "[DONE]".equals(data))
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data))
                .map(this::readDeltaText)
                .filter(text -> text != null && !text.isBlank());
    }

    public void testConnection(ModelConfig config) {
        try {
            restClient.post()
                    .uri(OpenAiCompatibleUrls.chatCompletionsUri(config.baseUrl()))
                    .headers(headers -> headers.setBearerAuth(config.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(chatRequest(config, new Prompt(new UserMessage(promptProperties.connectionTest().user())), false))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            throw new ModelConfigurationException("OpenAI-compatible connection failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> chatRequest(ModelConfig config, Prompt prompt, boolean stream) {
        return Map.of(
                "model", config.modelName(),
                "messages", messages(prompt),
                "temperature", config.resolvedTemperature(),
                "stream", stream
        );
    }

    private static List<Map<String, String>> messages(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(OpenAiCompatibleClient::message)
                .toList();
    }

    private static Map<String, String> message(Message message) {
        return Map.of(
                "role", message.getMessageType().getValue().toLowerCase(Locale.ROOT),
                "content", message.getText() == null ? "" : message.getText()
        );
    }

    private String readDeltaText(String data) {
        try {
            JsonNode choice = objectMapper.readTree(data).path("choices").path(0);
            JsonNode deltaContent = choice.path("delta").path("content");
            if (!deltaContent.isMissingNode()) {
                return deltaContent.asText("");
            }
            return choice.path("message").path("content").asText("");
        } catch (Exception ex) {
            throw new ModelConfigurationException("OpenAI-compatible stream response is not valid JSON", ex);
        }
    }
}
