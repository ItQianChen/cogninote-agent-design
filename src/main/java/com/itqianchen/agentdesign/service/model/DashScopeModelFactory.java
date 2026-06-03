package com.itqianchen.agentdesign.service.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingOptions;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;

@Component
public class DashScopeModelFactory {

    private final ObservationRegistry observationRegistry;
    private volatile CachedChatModel cachedChatModel;
    private volatile CachedEmbeddingModel cachedEmbeddingModel;

    public DashScopeModelFactory(ObjectProvider<ObservationRegistry> observationRegistry) {
        this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    public ChatModel chatModel(ModelConfig config) {
        ChatModelKey key = ChatModelKey.from(config);
        CachedChatModel cached = cachedChatModel;
        if (cached != null && cached.key().equals(key)) {
            return cached.model();
        }

        synchronized (this) {
            cached = cachedChatModel;
            if (cached != null && cached.key().equals(key)) {
                return cached.model();
            }
            // DashScope 模型创建会装配 HTTP 客户端和重试策略。
            // 按有效配置缓存，避免每次对话或索引都重建客户端。
            ChatModel model = buildChatModel(config);
            cachedChatModel = new CachedChatModel(key, model);
            return model;
        }
    }

    public EmbeddingModel embeddingModel(ModelConfig config) {
        EmbeddingModelKey key = EmbeddingModelKey.from(config);
        CachedEmbeddingModel cached = cachedEmbeddingModel;
        if (cached != null && cached.key().equals(key)) {
            return cached.model();
        }

        synchronized (this) {
            cached = cachedEmbeddingModel;
            if (cached != null && cached.key().equals(key)) {
                return cached.model();
            }
            // Embedding 在批量索引时调用频繁，同样需要按配置复用模型实例。
            EmbeddingModel model = buildEmbeddingModel(config);
            cachedEmbeddingModel = new CachedEmbeddingModel(key, model);
            return model;
        }
    }

    private ChatModel buildChatModel(ModelConfig config) {
        DashScopeChatEndpoint endpoint = DashScopeChatEndpoint.fromModel(config.modelName());
        DashScopeChatOptions options = DashScopeChatOptions.builder()
                .model(config.modelName())
                .temperature(config.resolvedTemperature())
                .stream(true)
                .incrementalOutput(true)
                .multiModel(endpoint.multiModel())
                .build();

        return DashScopeChatModel.builder()
                .dashScopeApi(api(config))
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.defaultInstance())
                .toolCallingManager(ToolCallingManager.builder()
                        .observationRegistry(observationRegistry)
                        .build())
                .observationRegistry(observationRegistry)
                .build();
    }

    private EmbeddingModel buildEmbeddingModel(ModelConfig config) {
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .model(config.modelName())
                .dimensions(config.resolvedEmbeddingDimensions())
                .build();

        return DashScopeEmbeddingModel.builder()
                .dashScopeApi(api(config))
                .metadataMode(MetadataMode.EMBED)
                .defaultOptions(options)
                .retryTemplate(RetryTemplate.defaultInstance())
                .observationRegistry(observationRegistry)
                .build();
    }

    private DashScopeApi api(ModelConfig config) {
        return DashScopeApi.builder()
                .baseUrl(DashScopeBaseUrls.toSpringAiAlibabaBaseUrl(config.baseUrl()))
                .apiKey(config.apiKey())
                .build();
    }

    enum DashScopeChatEndpoint {
        TEXT_GENERATION(false),
        MULTIMODAL_GENERATION(true);

        private final boolean multiModel;

        DashScopeChatEndpoint(boolean multiModel) {
            this.multiModel = multiModel;
        }

        boolean multiModel() {
            return multiModel;
        }

        static DashScopeChatEndpoint fromModel(String model) {
            String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
            /*
             * 阿里百炼的 /models 会同时返回文本、视觉、语音和新一代多模态模型。
             * Spring AI Alibaba 只靠 multiModel 决定最终 endpoint：
             * false -> /text-generation/generation，true -> /multimodal-generation/generation。
             * qwen3.6+/qwen3.7+ 这类模型即使只传文本，也必须走多模态 endpoint；
             * 否则百炼会返回 “url error, please check url”。
             */
            if (normalized.startsWith("qwen3.5-")
                    || normalized.startsWith("qwen3.6-")
                    || normalized.startsWith("qwen3.7-")
                    || normalized.contains("-vl-")
                    || normalized.contains("-omni-")
                    || normalized.contains("-image-")
                    || normalized.contains("-asr-")
                    || normalized.contains("-tts-")
                    || normalized.contains("-livetranslate-")) {
                return MULTIMODAL_GENERATION;
            }
            return TEXT_GENERATION;
        }
    }

    private record ChatModelKey(
            String nativeBaseUrl,
            String apiKey,
            String chatModel,
            double temperature,
            DashScopeChatEndpoint endpoint
    ) {
        private static ChatModelKey from(ModelConfig config) {
            return new ChatModelKey(
                    DashScopeBaseUrls.toSpringAiAlibabaBaseUrl(config.baseUrl()),
                    config.apiKey(),
                    config.modelName(),
                    config.resolvedTemperature(),
                    DashScopeChatEndpoint.fromModel(config.modelName())
            );
        }
    }

    private record EmbeddingModelKey(
            String nativeBaseUrl,
            String apiKey,
            String embeddingModel,
            int embeddingDimensions
    ) {
        private static EmbeddingModelKey from(ModelConfig config) {
            return new EmbeddingModelKey(
                    DashScopeBaseUrls.toSpringAiAlibabaBaseUrl(config.baseUrl()),
                    config.apiKey(),
                    config.modelName(),
                    config.resolvedEmbeddingDimensions()
            );
        }
    }

    private record CachedChatModel(ChatModelKey key, ChatModel model) {
    }

    private record CachedEmbeddingModel(EmbeddingModelKey key, EmbeddingModel model) {
    }
}


