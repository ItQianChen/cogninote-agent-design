package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    private static final int CHAT_MESSAGE_OVERHEAD_TOKENS = 4;

    private final EncodingRegistry encodingRegistry = Encodings.newLazyEncodingRegistry();

    public int estimate(String text) {
        return estimate(text, null);
    }

    public int estimate(String text, ModelConfig config) {
        return estimateWithMethod(text, config).tokens();
    }

    public int estimateChatMessage(String text, ModelConfig config) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return estimate(text, config) + CHAT_MESSAGE_OVERHEAD_TOKENS;
    }

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

    private EncodingChoice chooseEncoding(ModelConfig config) {
        String modelName = config == null ? "" : nullToBlank(config.modelName()).trim();
        if (!modelName.isBlank()) {
            try {
                return encodingRegistry.getEncodingForModel(modelName)
                        .map(encoding -> new EncodingChoice(encoding, "jtokkit:" + encoding.getName()))
                        .orElseGet(() -> fallbackEncoding(config, modelName));
            } catch (RuntimeException ignored) {
                return fallbackEncoding(config, modelName);
            }
        }
        return fallbackEncoding(config, modelName);
    }

    private EncodingChoice fallbackEncoding(ModelConfig config, String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        boolean preferO200K = config != null && config.provider() == ModelProvider.DASHSCOPE
                || normalized.contains("qwen")
                || normalized.contains("gpt-4o")
                || normalized.contains("gpt-5");
        EncodingType encodingType = preferO200K ? EncodingType.O200K_BASE : EncodingType.CL100K_BASE;
        Encoding encoding = encodingRegistry.getEncoding(encodingType);
        return new EncodingChoice(encoding, "jtokkit:" + encoding.getName());
    }

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

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public record TokenEstimate(int tokens, String method) {
    }

    private record EncodingChoice(Encoding encoding, String method) {
    }
}
