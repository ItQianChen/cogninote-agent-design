package com.itqianchen.agentdesign.service.document;

import java.util.regex.Pattern;

/** 清理可能进入 API 和运行记录的第三方错误文本。 */
public final class DocumentFailureSanitizer {

    private static final int MAX_DETAIL_LENGTH = 600;
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/-]+=*");
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|secret[_-]?key|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\\"',;\\s}]+[\\\"']?"
    );
    private static final Pattern DATA_URL_PATTERN = Pattern.compile("(?i)data:image/[^;]+;base64,[a-z0-9+/=]+");
    private static final Pattern LONG_BASE64_PATTERN = Pattern.compile("(?i)(?<![a-z0-9+/])[a-z0-9+/]{200,}={0,2}(?![a-z0-9+/])");
    private static final Pattern COMMON_KEY_PATTERN = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{4,}\\b");

    private DocumentFailureSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = BEARER_PATTERN.matcher(value).replaceAll("Bearer [REDACTED]");
        sanitized = SECRET_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
        sanitized = DATA_URL_PATTERN.matcher(sanitized).replaceAll("data:image/[REDACTED]");
        sanitized = LONG_BASE64_PATTERN.matcher(sanitized).replaceAll("[BASE64_REDACTED]");
        sanitized = COMMON_KEY_PATTERN.matcher(sanitized).replaceAll("sk-[REDACTED]");
        sanitized = sanitized.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (sanitized.length() > MAX_DETAIL_LENGTH) {
            return sanitized.substring(0, MAX_DETAIL_LENGTH) + "...";
        }
        return sanitized;
    }
}
