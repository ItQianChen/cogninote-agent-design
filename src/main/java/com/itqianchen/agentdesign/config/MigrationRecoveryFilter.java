package com.itqianchen.agentdesign.config;

import com.itqianchen.agentdesign.service.system.DatabaseMigrationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 恢复模式只开放诊断和迁移操作，避免业务请求落到临时恢复库。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MigrationRecoveryFilter extends OncePerRequestFilter {
    private final DatabaseMigrationService migrationService;

    public MigrationRecoveryFilter(DatabaseMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!migrationService.isRecoveryMode() || isRecoveryEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "Database migration recovery is active");
    }

    private static boolean isRecoveryEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/system/status")
                || path.startsWith("/api/system/migration/")
                || path.startsWith("/assets/")
                || path.equals("/")
                || path.endsWith(".html")
                || path.endsWith(".js")
                || path.endsWith(".css")
                || path.endsWith(".ico");
    }
}
