package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshot;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * AUTO 模式下的本地追问补全触发判断器。
 * <p>它不做精确短语命中，而是组合省略、指代、延续动作和完整问题反向信号进行轻量打分。</p>
 */
@Component
public class QueryContextualizerTriggerDecider {

    private static final int INVOKE_THRESHOLD = 3;
    private static final int VERY_SHORT_QUESTION_LENGTH = 12;
    private static final int SHORT_QUESTION_LENGTH = 24;

    private static final Pattern CONTEXT_REFERENCE_SIGNAL = Pattern.compile(
            "(它|他们|她们|这个|那个|这些|那些|这里|上面|前面|刚才|刚刚|前者|后者|该怎么|其|其中|这个呢|那个呢)"
    );
    private static final Pattern CONTINUATION_SIGNAL = Pattern.compile(
            "(继续|展开|详细|举例|例子|示例|代码|总结|再来|还有|区别|为什么|怎么实现|能不能|可以吗|这个呢|那个呢)"
    );
    private static final Pattern STANDALONE_TECHNICAL_TOKEN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_+#.\\-]{2,}"
    );
    private static final Pattern STANDALONE_CHINESE_TOPIC = Pattern.compile(
            "[\\p{IsHan}]{2,}(是什么|怎么|如何|为什么|区别|原理|流程|配置|实现|问题|方案)"
    );

    /**
     * 判断当前问题是否值得调用补全 Agent。
     * <p>没有历史时补全无法补充上下文，完整问题也应直接检索以减少额外延迟。</p>
     */
    public QueryContextualizerTriggerDecision decide(String question, ConversationMemorySnapshot snapshot) {
        if (!hasHistory(snapshot)) {
            return new QueryContextualizerTriggerDecision(false, "auto_no_history", 0);
        }

        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return new QueryContextualizerTriggerDecision(false, "auto_blank_question", 0);
        }

        int score = 0;
        int length = normalized.length();
        if (length <= VERY_SHORT_QUESTION_LENGTH) {
            score += 2;
        } else if (length <= SHORT_QUESTION_LENGTH) {
            score += 1;
        }
        if (CONTEXT_REFERENCE_SIGNAL.matcher(normalized).find()) {
            score += 3;
        }
        if (CONTINUATION_SIGNAL.matcher(normalized).find()) {
            score += 2;
        }
        if (looksLikeFragment(normalized)) {
            score += 1;
        }
        if (looksLikeStandaloneQuestion(normalized)) {
            score -= 3;
        }

        boolean shouldInvoke = score >= INVOKE_THRESHOLD;
        String reason = shouldInvoke ? "auto_follow_up_score" : "auto_standalone_question";
        return new QueryContextualizerTriggerDecision(shouldInvoke, reason, score);
    }

    /**
     * 判断快照中是否存在可用于补全的历史上下文。
     * <p>摘要和最近消息都算历史来源；压缩会话不能只看最近消息。</p>
     */
    public boolean hasHistory(ConversationMemorySnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            return true;
        }
        return snapshot.recentMessages() != null && !snapshot.recentMessages().isEmpty();
    }

    /**
     * 判断问题是否更像省略片段。
     * <p>短句、动宾式要求和未点名主体的问题，通常需要历史主题才能检索准确。</p>
     */
    private static boolean looksLikeFragment(String question) {
        if (question.endsWith("呢") || question.endsWith("？") && question.length() <= VERY_SHORT_QUESTION_LENGTH) {
            return true;
        }
        return !containsStandaloneTopic(question) && question.length() <= SHORT_QUESTION_LENGTH;
    }

    /**
     * 判断问题是否更像完整独立问题。
     * <p>包含明确技术词或中文主题，并带有“是什么/如何/为什么”等问法时，通常不需要补全。</p>
     */
    private static boolean looksLikeStandaloneQuestion(String question) {
        if (question.length() < VERY_SHORT_QUESTION_LENGTH) {
            return false;
        }
        return containsStandaloneTopic(question);
    }

    /**
     * 判断文本中是否有可独立检索的主题。
     * <p>英文技术词和中文主题都纳入，但只作为反向信号，不直接决定最终结果。</p>
     */
    private static boolean containsStandaloneTopic(String question) {
        return STANDALONE_TECHNICAL_TOKEN.matcher(question).find()
                || STANDALONE_CHINESE_TOPIC.matcher(question).find();
    }

    /**
     * 规范化用户输入。
     * <p>去掉多余空白，降低换行和复制文本对打分的影响。</p>
     */
    private static String normalize(String question) {
        return question == null ? "" : question.replaceAll("\\s+", " ").trim();
    }
}
