package com.itqianchen.agentdesign.service.graph;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 图谱字符串规范化工具。
 * <p>第一版只做确定性的本地规范化，不做 LLM 二次消歧，避免引入不可解释的合并。</p>
 */
@Component
public class GraphCanonicalizer {

    public String canonicalName(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    public String nodeType(String value) {
        return snakeUpper(value, "ENTITY");
    }

    public String relationType(String value) {
        return snakeUpper(value, "RELATED_TO");
    }

    public String displayText(String value, int maxLength) {
        String normalized = normalizeText(value);
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength)).strip();
    }

    public boolean quoteMatches(String content, String quote) {
        String normalizedContent = normalizeEvidenceText(content);
        String normalizedQuote = normalizeEvidenceText(quote);
        return !normalizedContent.isBlank()
                && !normalizedQuote.isBlank()
                && normalizedContent.contains(normalizedQuote);
    }

    public String stableId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String snakeUpper(String value, String fallback) {
        String normalized = normalizeText(value);
        if (normalized.isBlank()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder();
        boolean previousSeparator = false;
        for (int index = 0; index < normalized.length(); index++) {
            int codePoint = normalized.codePointAt(index);
            if (Character.charCount(codePoint) == 2) {
                index++;
            }
            if (Character.isLetterOrDigit(codePoint)) {
                builder.appendCodePoint(Character.toUpperCase(codePoint));
                previousSeparator = false;
                continue;
            }
            if (!previousSeparator && !builder.isEmpty()) {
                builder.append('_');
                previousSeparator = true;
            }
        }
        String result = builder.toString().replaceAll("_+$", "");
        return result.isBlank() ? fallback : result;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String normalizeEvidenceText(String value) {
        String normalized = normalizeText(value).toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < normalized.length(); index++) {
            int codePoint = normalized.codePointAt(index);
            if (Character.charCount(codePoint) == 2) {
                index++;
            }
            if (Character.isWhitespace(codePoint) || isPunctuation(codePoint)) {
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
}
