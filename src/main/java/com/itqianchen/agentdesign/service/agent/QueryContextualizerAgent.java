package com.itqianchen.agentdesign.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.chat.QueryContextualizerMode;
import com.itqianchen.agentdesign.domain.chat.QueryContextualizerProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.service.chat.ChatSettingsService;
import com.itqianchen.agentdesign.service.chat.ConversationMemoryEntry;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshot;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshotService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Query Contextualizer 智能体 定义一个智能体执行路径。
 * <p>负责把用户问题、系统提示词、记忆和检索上下文组合成模型调用。</p>
 */
@Service
public class QueryContextualizerAgent {

    private static final Logger log = LoggerFactory.getLogger(QueryContextualizerAgent.class);

    private final AiRuntimeFactory aiRuntimeFactory;
    private final ChatPromptProperties promptProperties;
    private final QueryContextualizerProperties properties;
    private final ChatSettingsService chatSettingsService;
    private final ConversationMemorySnapshotService memorySnapshotService;
    private final QueryContextualizerTriggerDecider triggerDecider;
    private final ObjectMapper objectMapper;

    /**
     * 注入 QueryContextualizerAgent 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public QueryContextualizerAgent(
            AiRuntimeFactory aiRuntimeFactory,
            ChatPromptProperties promptProperties,
            QueryContextualizerProperties properties,
            ChatSettingsService chatSettingsService,
            ConversationMemorySnapshotService memorySnapshotService,
            QueryContextualizerTriggerDecider triggerDecider,
            ObjectMapper objectMapper
    ) {
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.promptProperties = promptProperties;
        this.properties = properties;
        this.chatSettingsService = chatSettingsService;
        this.memorySnapshotService = memorySnapshotService;
        this.triggerDecider = triggerDecider;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 智能体编排 中的 contextualize 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public QueryContextualization contextualize(
            String requestId,
            String conversationId,
            String question,
            int maxMessageSequenceInclusive,
            ModelConfig chatConfig
    ) {
        QueryContextualizerMode mode = chatSettingsService.queryContextualizerMode();
        if (mode == QueryContextualizerMode.OFF) {
            logSkipped(requestId, conversationId, question, mode, "query_contextualizer_off", 0);
            return QueryContextualization.original(question, "query_contextualizer_off");
        }
        try {
            ConversationMemorySnapshot snapshot = memorySnapshotService.snapshot(
                    conversationId,
                    maxMessageSequenceInclusive
            );
            QueryContextualizerTriggerDecision decision = triggerDecision(mode, question, snapshot);
            if (!decision.shouldInvoke()) {
                logSkipped(requestId, conversationId, question, mode, decision.reason(), decision.score());
                return QueryContextualization.original(question, decision.reason());
            }
            return invokeModel(requestId, conversationId, question, chatConfig, snapshot, mode, decision);
        } catch (RuntimeException ex) {
            log.warn(
                    "query_contextualizer_failed requestId={} conversationId={} originalQuestion={} reason={}",
                    requestId,
                    conversationId,
                    question,
                    ex.getMessage()
            );
            log.debug("query_contextualizer_failed_stacktrace requestId={} conversationId={}", requestId, conversationId, ex);
            return QueryContextualization.original(question, "query_contextualizer_failed");
        }
    }

    /**
     * 在 AUTO 模式下为弱检索结果执行一次补全重试。
     * <p>只有原问题没有命中知识库且存在历史时才使用，避免完整问题场景无意义地额外调用模型。</p>
     */
    public QueryContextualization contextualizeForWeakRetrieval(
            String requestId,
            String conversationId,
            String question,
            int maxMessageSequenceInclusive,
            ModelConfig chatConfig
    ) {
        QueryContextualizerMode mode = chatSettingsService.queryContextualizerMode();
        if (mode != QueryContextualizerMode.AUTO) {
            return QueryContextualization.original(question, "weak_retrieval_retry_not_auto");
        }
        try {
            ConversationMemorySnapshot snapshot = memorySnapshotService.snapshot(
                    conversationId,
                    maxMessageSequenceInclusive
            );
            if (!triggerDecider.hasHistory(snapshot)) {
                logSkipped(requestId, conversationId, question, mode, "auto_weak_retrieval_no_history", 0);
                return QueryContextualization.original(question, "auto_weak_retrieval_no_history");
            }
            QueryContextualizerTriggerDecision decision = new QueryContextualizerTriggerDecision(
                    true,
                    "auto_weak_retrieval_retry",
                    0
            );
            return invokeModel(requestId, conversationId, question, chatConfig, snapshot, mode, decision);
        } catch (RuntimeException ex) {
            log.warn(
                    "query_contextualizer_weak_retry_failed requestId={} conversationId={} originalQuestion={} reason={}",
                    requestId,
                    conversationId,
                    question,
                    ex.getMessage()
            );
            log.debug("query_contextualizer_weak_retry_failed_stacktrace requestId={} conversationId={}", requestId, conversationId, ex);
            return QueryContextualization.original(question, "query_contextualizer_weak_retry_failed");
        }
    }

    /**
     * 根据模式计算本轮是否调用补全模型。
     * <p>ALWAYS 保留旧行为；AUTO 交给本地打分器；OFF 在入口处已经提前返回。</p>
     */
    private QueryContextualizerTriggerDecision triggerDecision(
            QueryContextualizerMode mode,
            String question,
            ConversationMemorySnapshot snapshot
    ) {
        if (mode == QueryContextualizerMode.ALWAYS) {
            return new QueryContextualizerTriggerDecision(true, "always_mode", 0);
        }
        return triggerDecider.decide(question, snapshot);
    }

    /**
     * 调用补全模型并解析 JSON 响应。
     * <p>所有模型调用都经过这个方法，便于统一日志、兜底和 Prompt 上下文格式。</p>
     */
    private QueryContextualization invokeModel(
            String requestId,
            String conversationId,
            String question,
            ModelConfig chatConfig,
            ConversationMemorySnapshot snapshot,
            QueryContextualizerMode mode,
            QueryContextualizerTriggerDecision decision
    ) {
        String history = formatHistory(snapshot);
        // 补全 Agent 只生成检索 query，不参与最终回答，也不改写用户消息原文。
        String response = aiRuntimeFactory.chatRuntime(chatConfig)
                .callText(
                        promptProperties.queryContextualizer().system(),
                        queryContextualizerUserPrompt(question, history)
                );
        QueryContextualization contextualization = parseResponse(question, response);
        log.info(
                "query_contextualized requestId={} conversationId={} mode={} triggerReason={} triggerScore={} rewritten={} confidence={} originalQuestion={} retrievalQuery={} reason={}",
                requestId,
                conversationId,
                mode,
                decision.reason(),
                decision.score(),
                contextualization.rewritten(),
                contextualization.confidence(),
                question,
                contextualization.retrievalQuery(),
                contextualization.reason()
        );
        return contextualization;
    }

    /**
     * 记录 AUTO/OFF 模式下跳过补全模型的原因。
     * <p>日志用于观察触发策略，不把这些内部细节暴露给前端聊天记录。</p>
     */
    private void logSkipped(
            String requestId,
            String conversationId,
            String question,
            QueryContextualizerMode mode,
            String reason,
            int score
    ) {
        log.debug(
                "query_contextualizer_skipped requestId={} conversationId={} mode={} reason={} score={} question={}",
                requestId,
                conversationId,
                mode,
                reason,
                score,
                question
        );
    }

    /**
     * 执行 智能体编排 中的 query Contextualizer User Prompt 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private String queryContextualizerUserPrompt(String question, String history) {
        return promptProperties.queryContextualizer().user()
                .replace("{question}", question)
                .replace("{history}", history);
    }

    /**
     * 执行 智能体编排 中的 format History 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private String formatHistory(ConversationMemorySnapshot snapshot) {
        List<ConversationMemoryEntry> entries = snapshot.recentMessages();
        int maxMessages = properties.resolvedMaxHistoryMessages();
        List<ConversationMemoryEntry> selected = maxMessages == 0 || entries.isEmpty()
                ? List.of()
                : entries.size() <= maxMessages
                ? entries
                : entries.subList(entries.size() - maxMessages, entries.size());
        StringBuilder builder = new StringBuilder();
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            builder.append("会话摘要：\n")
                    .append(snapshot.summary().strip());
        }
        if (!selected.isEmpty()) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append("最近原文消息：");
        }
        for (ConversationMemoryEntry entry : selected) {
            if (entry.content() == null || entry.content().isBlank()) {
                continue;
            }
            builder.append('\n');
            builder.append(entry.role() == ChatMessageRole.USER ? "用户" : "助手")
                    .append("：")
                    .append(entry.content().strip());
        }
        return builder.isEmpty() ? "无历史消息。" : builder.toString();
    }

    /**
     * 解析 parse 响应 输入。
     * <p>将外部文本或结构转换为模块内部可直接使用的对象。</p>
     */
    private QueryContextualization parseResponse(String question, String response) {
        String json = extractJson(response);
        ContextualizerModelResponse parsed;
        try {
            // JSON 编解码在边界层完成，避免序列化细节泄漏到业务对象。
            parsed = objectMapper.readValue(json, ContextualizerModelResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid contextualizer json", ex);
        }
        if (parsed.shouldRewrite() == null || parsed.reason() == null || parsed.confidence() == null) {
            return QueryContextualization.original(question, "contextualizer_fields_missing");
        }
        if (!parsed.shouldRewrite()) {
            return QueryContextualization.original(question, nullToReason(parsed.reason(), "model_kept_original"));
        }
        String rewrittenQuery = parsed.rewrittenQuery() == null ? "" : parsed.rewrittenQuery().strip();
        if (rewrittenQuery.isBlank()) {
            return QueryContextualization.original(question, "rewritten_query_blank");
        }
        if (rewrittenQuery.length() > properties.resolvedMaxRewrittenQueryLength()) {
            return QueryContextualization.original(question, "rewritten_query_too_long");
        }
        return new QueryContextualization(
                question,
                rewrittenQuery,
                true,
                nullToReason(parsed.reason(), "model_rewrote_query"),
                /**
                 * 规范化 normalize Confidence 输入。
                 * <p>后续逻辑只处理受控取值，减少重复分支和边界判断。</p>
                 */
                normalizeConfidence(parsed.confidence())
        );
    }

    /**
     * 执行 智能体编排 中的 extract Json 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("contextualizer response is blank");
        }
        String stripped = response.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("contextualizer response does not contain json object");
        }
        return stripped.substring(start, end + 1);
    }

    /**
     * 执行 智能体编排 中的 null To Reason 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static String nullToReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * 规范化 normalize Confidence 输入。
     * <p>后续逻辑只处理受控取值，减少重复分支和边界判断。</p>
     */
    private static double normalizeConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return 0.0;
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }

    /**
     * Contextualizer Model 响应 定义返回给前端的 智能体编排 响应结构。
     * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
     */
    private record ContextualizerModelResponse(
            Boolean shouldRewrite,
            String rewrittenQuery,
            String reason,
            Double confidence
    ) {
    }
}
