package com.itqianchen.agentdesign.service.search;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.exception.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.interfaces.search.EmbeddingGateway;
import com.itqianchen.agentdesign.domain.properties.search.EmbeddingProperties;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.service.knowledge.KnowledgeMaintenanceProgressReporter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Embedding 模型的运行时适配器。
 *
 * <p>优先使用数据库中的 active EMBEDDING 配置；没有配置时才回退到 Spring Boot 自动装配模型。
 * 不可用时由检索层降级到关键词检索，而不是阻止应用启动。</p>
 */
@Component
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private static final int MAX_RATE_LIMIT_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 1_000L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long RATE_WINDOW_MS = 60_000L;

    private final Optional<EmbeddingModel> embeddingModel;
    private final ModelConfigRepository modelConfigRepository;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final EmbeddingProperties embeddingProperties;
    private final KnowledgeMaintenanceProgressReporter progressReporter;
    private final EmbeddingRateLimiter rateLimiter = new EmbeddingRateLimiter();
    private final String embeddingProvider;

    /**
     * 注入 Embedding 运行时依赖。
     *
     * @param embeddingModel Spring Boot 自动配置的 EmbeddingModel
     * @param modelConfigRepository 模型配置仓储
     * @param aiRuntimeFactory AI 运行时工厂
     * @param embeddingProperties Embedding 配置
     * @param progressReporter 维护任务进度上报器
     * @param embeddingProvider 自动配置 Provider 名称
     */
    public SpringAiEmbeddingGateway(
            Optional<EmbeddingModel> embeddingModel,
            ModelConfigRepository modelConfigRepository,
            AiRuntimeFactory aiRuntimeFactory,
            EmbeddingProperties embeddingProperties,
            KnowledgeMaintenanceProgressReporter progressReporter,
            @Value("${spring.ai.model.embedding:none}") String embeddingProvider
    ) {
        this.embeddingModel = embeddingModel;
        this.modelConfigRepository = modelConfigRepository;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.embeddingProperties = embeddingProperties;
        this.progressReporter = progressReporter;
        this.embeddingProvider = embeddingProvider;
    }

    /**
     * 判断当前是否有可用 Embedding 能力。
     *
     * @return 是否可生成向量
     */
    @Override
    public boolean isAvailable() {
        Optional<ModelConfig> configuredModel = modelConfigRepository.findActive(ModelConfigRole.EMBEDDING)
                .filter(ModelConfig::hasApiKey);
        if (configuredModel.isPresent()) {
            return true;
        }

        if (embeddingModel.isEmpty() || "none".equalsIgnoreCase(embeddingProvider)) {
            return false;
        }

        return true;
    }

    /**
     * 返回当前 Embedding 维度。
     *
     * @return 向量维度
     */
    @Override
    public int dimensions() {
        return modelConfigRepository.findActive(ModelConfigRole.EMBEDDING)
                .filter(ModelConfig::hasApiKey)
                .map(ModelConfig::resolvedEmbeddingDimensions)
                .orElse(embeddingProperties.dimensions());
    }

    /**
     * 为文档 chunk 生成向量。
     *
     * @param texts 文档 chunk 文本
     * @return 向量列表
     */
    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embedTexts(texts, EmbeddingPurpose.DOCUMENT);
    }

    /**
     * 为检索 query 生成向量。
     *
     * @param query 检索 query
     * @return query 向量
     */
    @Override
    public float[] embedQuery(String query) {
        List<float[]> vectors = embedTexts(List.of(query), EmbeddingPurpose.QUERY);
        return vectors.getFirst();
    }

    /**
     * 批量生成向量并校验维度。
     *
     * @param texts 待生成向量的文本
     * @param purpose query 或 document 场景
     * @return 向量列表
     */
    private List<float[]> embedTexts(List<String> texts, EmbeddingPurpose purpose) {
        Optional<ModelConfig> configuredModel = modelConfigRepository.findActive(ModelConfigRole.EMBEDDING)
                .filter(ModelConfig::hasApiKey);
        if (configuredModel.isEmpty() && !isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured");
        }

        int expectedDimensions = configuredModel
                .map(ModelConfig::resolvedEmbeddingDimensions)
                .orElseGet(embeddingProperties::dimensions);
        int batchSize = configuredModel
                .map(ModelConfig::resolvedEmbeddingBatchSize)
                .orElseGet(embeddingProperties::normalizedBatchSize);
        EmbeddingRateLimitSettings rateLimitSettings = configuredModel
                .map(config -> new EmbeddingRateLimitSettings(
                        config.resolvedEmbeddingRequestsPerMinute(),
                        config.resolvedEmbeddingTokensPerMinute()
                ))
                .orElseGet(() -> new EmbeddingRateLimitSettings(
                        embeddingProperties.normalizedRequestsPerMinute(),
                        embeddingProperties.normalizedTokensPerMinute()
                ));

        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            vectors.addAll(embedSlice(texts.subList(start, end), purpose, configuredModel, rateLimitSettings));
        }

        for (float[] vector : vectors) {
            if (vector.length != expectedDimensions) {
                throw new EmbeddingUnavailableException(
                        "Embedding dimensions mismatch: expected "
                                + expectedDimensions
                                + " but got "
                                + vector.length
                );
            }
        }

        return vectors;
    }

    /**
     * 读取自动配置的 EmbeddingModel。
     *
     * @return EmbeddingModel
     */
    private EmbeddingModel activeEmbeddingModel() {
        return embeddingModel.orElseThrow(() ->
                new EmbeddingUnavailableException("Embedding model is not configured"));
    }

    /**
     * 按批次生成向量。
     *
     * @param texts 当前批次文本
     * @param purpose query 或 document 场景
     * @return 当前批次向量
     */
    private List<float[]> embedSlice(
            List<String> texts,
            EmbeddingPurpose purpose,
            Optional<ModelConfig> configuredModel,
            EmbeddingRateLimitSettings rateLimitSettings
    ) {
        return withProviderRateLimitRetry(texts, rateLimitSettings, () -> configuredModel
                .map(config -> embedConfigured(config, texts, purpose))
                .orElseGet(() -> embedAutoConfigured(texts, purpose)));
    }

    /**
     * 使用数据库中激活的 Embedding 配置生成向量。
     *
     * @param config Embedding 配置
     * @param texts 文本列表
     * @param purpose query 或 document 场景
     * @return 向量列表
     */
    private List<float[]> embedConfigured(ModelConfig config, List<String> texts, EmbeddingPurpose purpose) {
        if (purpose == EmbeddingPurpose.QUERY) {
            return List.of(aiRuntimeFactory.embeddingRuntime(config).embedQuery(texts.getFirst()));
        }
        return aiRuntimeFactory.embeddingRuntime(config).embedDocuments(texts);
    }

    /**
     * 使用 Spring Boot 自动配置模型生成向量。
     *
     * @param texts 文本列表
     * @param purpose query 或 document 场景
     * @return 向量列表
     */
    private List<float[]> embedAutoConfigured(List<String> texts, EmbeddingPurpose purpose) {
        EmbeddingModel model = activeEmbeddingModel();
        if (purpose == EmbeddingPurpose.QUERY) {
            return List.of(model.embed(texts.getFirst()));
        }
        return model.embed(texts);
    }

    private List<float[]> withProviderRateLimitRetry(
            List<String> texts,
            EmbeddingRateLimitSettings settings,
            Supplier<List<float[]>> operation
    ) {
        for (int attempt = 1; attempt <= MAX_RATE_LIMIT_RETRIES + 1; attempt++) {
            rateLimiter.awaitPermit(texts, settings);
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                if (!isProviderRateLimit(ex) || attempt > MAX_RATE_LIMIT_RETRIES) {
                    if (isProviderRateLimit(ex)) {
                        throw new EmbeddingUnavailableException(
                                "供应商 Embedding 限流，已自动退避重试但仍失败；这不是文档解析失败，稍后可使用补写索引继续。",
                                ex
                        );
                    }
                    throw ex;
                }
                long backoffMs = backoffMs(attempt);
                String message = "供应商限流，已等待后重试；这不是文档解析失败。第 "
                        + attempt
                        + "/"
                        + MAX_RATE_LIMIT_RETRIES
                        + " 次重试，等待约 "
                        + Math.max(1, backoffMs / 1000)
                        + " 秒。";
                progressReporter.reportEmbeddingRateLimit(message);
                sleep(backoffMs, ex);
            }
        }
        throw new EmbeddingUnavailableException("Embedding provider rate limit retry exhausted");
    }

    private static boolean isProviderRateLimit(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("429")
                        || lower.contains("rate limit")
                        || lower.contains("rate limiting")
                        || lower.contains("too many requests")
                        || lower.contains("tpm limit")
                        || lower.contains("rpm limit")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static long backoffMs(int attempt) {
        long exponential = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS << Math.min(attempt - 1, 5));
        long jitter = ThreadLocalRandom.current().nextLong(250L, 1_001L);
        return Math.min(MAX_BACKOFF_MS, exponential + jitter);
    }

    private static void sleep(long millis, RuntimeException originalError) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmbeddingUnavailableException("Embedding rate limit retry was interrupted", originalError);
        }
    }

    private record EmbeddingRateLimitSettings(
            int requestsPerMinute,
            int tokensPerMinute
    ) {
    }

    private static final class EmbeddingRateLimiter {
        private final Deque<TokenWindowEntry> tokenWindow = new ArrayDeque<>();
        private long lastRequestAt;
        private long tokenWindowSum;

        private synchronized void awaitPermit(List<String> texts, EmbeddingRateLimitSettings settings) {
            int tokenCost = Math.min(estimateTokens(texts), settings.tokensPerMinute());
            while (true) {
                long now = System.currentTimeMillis();
                if (evictExpired(now)) {
                    notifyAll();
                }
                long rpmWaitMs = Math.max(0L, minRequestIntervalMs(settings.requestsPerMinute()) - (now - lastRequestAt));
                long tpmWaitMs = tokenWindowSum + tokenCost <= settings.tokensPerMinute()
                        ? 0L
                        : Math.max(1L, RATE_WINDOW_MS - (now - tokenWindow.peekFirst().timestampMs()));
                long waitMs = Math.max(rpmWaitMs, tpmWaitMs);
                if (waitMs <= 0L) {
                    lastRequestAt = now;
                    tokenWindow.addLast(new TokenWindowEntry(now, tokenCost));
                    tokenWindowSum += tokenCost;
                    return;
                }
                try {
                    wait(waitMs);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingUnavailableException("Embedding rate limiter was interrupted", ex);
                }
            }
        }

        private boolean evictExpired(long now) {
            boolean evicted = false;
            while (!tokenWindow.isEmpty() && now - tokenWindow.peekFirst().timestampMs() >= RATE_WINDOW_MS) {
                tokenWindowSum -= tokenWindow.removeFirst().tokens();
                evicted = true;
            }
            return evicted;
        }

        private static long minRequestIntervalMs(int requestsPerMinute) {
            return Math.max(1L, RATE_WINDOW_MS / Math.max(1, requestsPerMinute));
        }

        private static int estimateTokens(List<String> texts) {
            int chars = texts.stream()
                    .mapToInt(text -> text == null ? 0 : text.length())
                    .sum();
            return Math.max(1, (int) Math.ceil(chars / 3.0));
        }
    }

    private record TokenWindowEntry(long timestampMs, int tokens) {
    }

    private enum EmbeddingPurpose {
        DOCUMENT,
        QUERY
    }
}


