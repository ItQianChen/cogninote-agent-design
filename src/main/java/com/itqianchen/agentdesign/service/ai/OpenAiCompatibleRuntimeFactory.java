package com.itqianchen.agentdesign.service.ai;

import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiEmbeddingRuntime;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.service.model.OpenAiCompatibleUrls;
import io.micrometer.observation.ObservationRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 为用户自定义的 OpenAI-compatible Base URL 构造运行时。
 *
 * <p>Spring Boot 自动配置无法覆盖界面保存的 endpoint，所以这里手动构建 OpenAiApi，
 * 并按完整配置缓存 Chat/Embedding 运行时。</p>
 */
@Component
public class OpenAiCompatibleRuntimeFactory {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final RestClient.Builder restClientBuilder;
    private final WebClient.Builder webClientBuilder;
    private final ObservationRegistry observationRegistry;
    private volatile CachedChatRuntime cachedChatRuntime;
    private volatile CachedEmbeddingRuntime cachedEmbeddingRuntime;

    /**
     * 注入 HTTP 客户端构造器和观测注册器。
     *
     * <p>构造器在创建 OpenAiApi 时会 clone，避免运行时配置污染全局 Spring AI 客户端。</p>
     *
     * @param restClientBuilder Spring RestClient 构造器
     * @param webClientBuilder Spring WebClient 构造器
     * @param observationRegistry Micrometer 观测注册器；缺省时使用 NOOP
     */
    public OpenAiCompatibleRuntimeFactory(
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            ObjectProvider<ObservationRegistry> observationRegistry
    ) {
        this.restClientBuilder = restClientBuilder;
        this.webClientBuilder = webClientBuilder;
        this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    /**
     * 获取或创建 OpenAI-compatible Chat 运行时。
     *
     * <p>缓存 key 覆盖 URL、密钥、模型、温度和推理等级；任一字段变化都会构建新客户端。</p>
     *
     * @param config 已归一化的 Chat 配置
     * @return Chat 运行时
     */
    public AiChatRuntime chatRuntime(ModelConfig config) {
        ChatRuntimeKey key = ChatRuntimeKey.from(config);
        CachedChatRuntime cached = cachedChatRuntime;
        if (cached != null && cached.key().equals(key)) {
            return cached.runtime();
        }

        synchronized (this) {
            cached = cachedChatRuntime;
            if (cached != null && cached.key().equals(key)) {
                return cached.runtime();
            }
            /*
             * OpenAI-compatible 的 Base URL 是用户运行时配置，不能交给 Spring Boot 自动配置。
             * 这里显式构造 OpenAiApi，并把 path 固定为 Base URL + /chat/completions。
             */
            AiChatRuntime runtime = new SpringAiChatRuntime("OpenAI-compatible", buildChatModel(config));
            cachedChatRuntime = new CachedChatRuntime(key, runtime);
            return runtime;
        }
    }

    /**
     * 获取或创建 OpenAI-compatible Embedding 运行时。
     *
     * <p>维度变化会影响 Lucene 向量字段，必须作为缓存 key 的一部分。</p>
     *
     * @param config 已归一化的 Embedding 配置
     * @return Embedding 运行时
     */
    public AiEmbeddingRuntime embeddingRuntime(ModelConfig config) {
        EmbeddingRuntimeKey key = EmbeddingRuntimeKey.from(config);
        CachedEmbeddingRuntime cached = cachedEmbeddingRuntime;
        if (cached != null && cached.key().equals(key)) {
            return cached.runtime();
        }

        synchronized (this) {
            cached = cachedEmbeddingRuntime;
            if (cached != null && cached.key().equals(key)) {
                return cached.runtime();
            }
            AiEmbeddingRuntime runtime = new SpringAiEmbeddingRuntime(buildEmbeddingModel(config));
            cachedEmbeddingRuntime = new CachedEmbeddingRuntime(key, runtime);
            return runtime;
        }
    }

    /**
     * 构造 Spring AI OpenAI ChatModel。
     *
     * @param config 用户保存的 Chat 配置
     * @return 可调用的 ChatModel
     */
    private OpenAiChatModel buildChatModel(ModelConfig config) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(config.modelName())
                .temperature(config.resolvedTemperature());
        if (!"NONE".equals(config.resolvedReasoningEffort())) {
            options.reasoningEffort(config.resolvedReasoningEffort().toLowerCase(Locale.ROOT));
            options.extraBody(Map.of("enable_thinking", true));
        }

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi(config))
                .defaultOptions(options.build())
                .toolCallingManager(ToolCallingManager.builder()
                        .observationRegistry(observationRegistry)
                        .build())
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 构造 Spring AI OpenAI EmbeddingModel。
     *
     * @param config 用户保存的 Embedding 配置
     * @return 可调用的 EmbeddingModel
     */
    private OpenAiEmbeddingModel buildEmbeddingModel(ModelConfig config) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(config.modelName())
                .dimensions(config.resolvedEmbeddingDimensions())
                .build();

        return new OpenAiEmbeddingModel(
                openAiApi(config),
                MetadataMode.EMBED,
                options,
                RetryUtils.DEFAULT_RETRY_TEMPLATE,
                observationRegistry
        );
    }

    /**
     * 构造指向用户 Base URL 的 OpenAiApi。
     *
     * <p>路径固定追加标准 chat/embedding endpoint，Base URL 的归一化由 OpenAiCompatibleUrls 保证。</p>
     *
     * @param config 用户保存的模型配置
     * @return Spring AI OpenAiApi
     */
    private OpenAiApi openAiApi(ModelConfig config) {
        return OpenAiApi.builder()
                .baseUrl(OpenAiCompatibleUrls.normalizeBaseUrl(config.baseUrl()))
                .apiKey(config.apiKey())
                .completionsPath(CHAT_COMPLETIONS_PATH)
                .embeddingsPath(EMBEDDINGS_PATH)
                .restClientBuilder(restClientBuilder.clone())
                .webClientBuilder(webClientBuilder.clone())
                .responseErrorHandler(RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER)
                .build();
    }

    /**
     * Chat 运行时缓存 key，必须包含所有会影响请求地址、认证和生成参数的字段。
     */
    private record ChatRuntimeKey(
            String baseUrl,
            String apiKey,
            String modelName,
            double temperature,
            String reasoningEffort
    ) {
        /**
         * 从模型配置提取 Chat 运行时缓存维度。
         *
         * @param config 用户保存的 Chat 配置
         * @return 可比较的缓存 key
         */
        private static ChatRuntimeKey from(ModelConfig config) {
            return new ChatRuntimeKey(
                    OpenAiCompatibleUrls.normalizeBaseUrl(config.baseUrl()),
                    config.apiKey(),
                    config.modelName(),
                    config.resolvedTemperature(),
                    config.resolvedReasoningEffort()
            );
        }
    }

    /**
     * Embedding 运行时缓存 key，维度变化会改变返回向量形状，不能复用旧实例。
     */
    private record EmbeddingRuntimeKey(
            String baseUrl,
            String apiKey,
            String modelName,
            int embeddingDimensions
    ) {
        /**
         * 从模型配置提取 Embedding 运行时缓存维度。
         *
         * @param config 用户保存的 Embedding 配置
         * @return 可比较的缓存 key
         */
        private static EmbeddingRuntimeKey from(ModelConfig config) {
            return new EmbeddingRuntimeKey(
                    OpenAiCompatibleUrls.normalizeBaseUrl(config.baseUrl()),
                    config.apiKey(),
                    config.modelName(),
                    config.resolvedEmbeddingDimensions()
            );
        }
    }

    /**
     * 与缓存 key 绑定的 Chat 运行时实例，配置变化时整体替换。
     */
    private record CachedChatRuntime(ChatRuntimeKey key, AiChatRuntime runtime) {
        private CachedChatRuntime {
            Objects.requireNonNull(key);
            Objects.requireNonNull(runtime);
        }
    }

    /**
     * 与缓存 key 绑定的 Embedding 运行时实例，避免并发读取到半更新状态。
     */
    private record CachedEmbeddingRuntime(EmbeddingRuntimeKey key, AiEmbeddingRuntime runtime) {
        private CachedEmbeddingRuntime {
            Objects.requireNonNull(key);
            Objects.requireNonNull(runtime);
        }
    }
}
