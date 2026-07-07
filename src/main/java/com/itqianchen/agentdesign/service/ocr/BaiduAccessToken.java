package com.itqianchen.agentdesign.service.ocr;

/**
 * 百度 OCR access_token 响应。
 */
public record BaiduAccessToken(
        String token,
        long expiresInSeconds
) {
}
