package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * TokenEstimator 负责估算 token 用量。
 * <p>估算结果用于上下文裁剪、压缩策略和前端占用展示。</p>
 */
@Component
public class TokenEstimator {

    private static final int CHAT_MESSAGE_OVERHEAD_TOKENS = 4;

    private final EncodingRegistry encodingRegistry = Encodings.newLazyEncodingRegistry();

    /**
     * 使用默认策略估算 token。
     *
     * @param text 待估算文本
     * @return token 数
     */
    public int estimate(String text) {
        return estimate(text, null);
    }

    /**
     * 按模型配置估算 token。
     *
     * @param text 待估算文本
     * @param config 模型配置；为空时使用兜底编码
     * @return token 数
     */
    public int estimate(String text, ModelConfig config) {
        return estimateWithMethod(text, config).tokens();
    }

    /**
     * 估算聊天消息 token。
     *
     * <p>在文本 token 基础上追加固定 message overhead，贴近 Chat Completion 的上下文预算。</p>
     *
     * @param text 消息文本
     * @param config 模型配置
     * @return token 数
     */
    public int estimateChatMessage(String text, ModelConfig config) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return estimate(text, config) + CHAT_MESSAGE_OVERHEAD_TOKENS;
    }

    /**
     * 估算 token 并返回估算方法。
     *
     * <p>JTokkit 不支持或运行失败时回退到保守启发式，避免低估导致上下文超限。</p>
     *
     * @param text 待估算文本
     * @param config 模型配置
     * @return token 数和方法名称
     */
    public TokenEstimate estimateWithMethod(String text, ModelConfig config) {
        if (text == null || text.isBlank()) {
            return new TokenEstimate(0, "empty");
        }
        try {
            EncodingChoice choice = chooseEncoding(config);
            return new TokenEstimate(Math.max(1, choice.encoding().countTokens(text)), choice.method());
        } catch (RuntimeException ex) {
            return new TokenEstimate(heuristicEstimate(text), "heuristic:mixed-script");
        }
    }

    /**
     * 选择 choose Encoding 对应的可用策略。
     * <p>优先走精确匹配，失败时回退到兼容方案。</p>
     *
     * @param config 模型配置
     * @return tokenizer 选择结果
     */
    private EncodingChoice chooseEncoding(ModelConfig config) {
        String modelName = config == null ? "" : nullToBlank(config.modelName()).trim();
        if (!modelName.isBlank()) {
            try {
                // JTokkit tokenizer 选择决定上下文预算估算口径，失败时必须走保守兜底。
                return encodingRegistry.getEncodingForModel(modelName)
                        .map(encoding -> new EncodingChoice(encoding, "jtokkit:" + encoding.getName()))
                        .orElseGet(() -> fallbackEncoding(config, modelName));
            } catch (RuntimeException ignored) {
                return fallbackEncoding(config, modelName);
            }
        }
        return fallbackEncoding(config, modelName);
    }

    /**
     * 选择 fallback Encoding 对应的可用策略。
     * <p>优先走精确匹配，失败时回退到兼容方案。</p>
     *
     * @param config 模型配置
     * @param modelName 已归一化模型名
     * @return tokenizer 选择结果
     */
    private EncodingChoice fallbackEncoding(ModelConfig config, String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        boolean preferO200K = normalized.contains("qwen")
                || normalized.contains("gpt-4o")
                || normalized.contains("gpt-5");
        EncodingType encodingType = preferO200K ? EncodingType.O200K_BASE : EncodingType.CL100K_BASE;
        // JTokkit tokenizer 选择决定上下文预算估算口径，失败时必须走保守兜底。
        Encoding encoding = encodingRegistry.getEncoding(encodingType);
        return new EncodingChoice(encoding, "jtokkit:" + encoding.getName());
    }

    /**
     * 在 tokenizer 不可用时执行保守 token 估算。
     * <p>混合中英文文本按更谨慎的规则估算，避免上下文预算被低估。</p>
     *
     * @param text 待估算文本
     * @return token 数
     */
    private static int heuristicEstimate(String text) {
        double tokens = 0.0;
        int asciiRunLength = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                asciiRunLength++;
                continue;
            }
            if (asciiRunLength > 0) {
                tokens += Math.ceil(asciiRunLength / 4.0);
                asciiRunLength = 0;
            }
            tokens += Character.isWhitespace(codePoint) ? 0.25 : 1.0;
        }
        if (asciiRunLength > 0) {
            tokens += Math.ceil(asciiRunLength / 4.0);
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    /**
     * 将可空字符串转换为空串。
     * <p>调用方可直接进行 trim、contains 等字符串操作。</p>
     *
     * @param value 可空字符串
     * @return 非空字符串
     */
    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record TokenEstimate(int tokens, String method) {
    }

    private record EncodingChoice(Encoding encoding, String method) {
    }
}
