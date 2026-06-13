package com.itqianchen.agentdesign.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.common.api.ApiErrorCode;
import com.itqianchen.agentdesign.common.api.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 桌面模式下保护本机 HTTP API。
 *
 * <p>后端虽然只监听 127.0.0.1，但同一台机器上的其他进程也能访问本机端口。桌面壳启动时生成一次性
 * session token，并要求所有 /api/** 请求带上固定 header，避免本机网页或旁路进程直接调用业务 API。</p>
 */
@Component
public class DesktopSessionTokenFilter extends OncePerRequestFilter {

    public static final String SESSION_TOKEN_HEADER = "X-CogniNote-Desktop-Session";

    private final boolean desktopMode;
    private final String sessionToken;
    private final ObjectMapper objectMapper;

    /**
     * 注入桌面令牌配置。
     *
     * @param desktopMode 是否由桌面壳启动
     * @param sessionToken 桌面壳生成的临时令牌
     * @param objectMapper JSON 序列化器
     */
    public DesktopSessionTokenFilter(
            @Value("${app.desktop.enabled:false}") boolean desktopMode,
            @Value("${app.desktop.session-token:}") String sessionToken,
            ObjectMapper objectMapper
    ) {
        this.desktopMode = desktopMode;
        this.sessionToken = sessionToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !desktopMode || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String providedToken = request.getHeader(SESSION_TOKEN_HEADER);
        if (isValidToken(providedToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(ApiErrorCode.UNAUTHORIZED, "Desktop session token is missing or invalid")
        );
    }

    private boolean isValidToken(String providedToken) {
        if (!StringUtils.hasText(sessionToken) || !StringUtils.hasText(providedToken)) {
            return false;
        }
        byte[] expected = sessionToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = providedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
