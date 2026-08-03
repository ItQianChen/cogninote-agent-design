package com.itqianchen.agentdesign.domain.dto.system;

/**
 * System Status 响应 定义返回给前端的 系统状态 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record SystemStatusResponse(
        String appName,
        String version,
        String status,
        String dataDir,
        boolean desktopMode,
        String mode,
        String databaseStatus,
        int detectedSchemaVersion,
        int latestSchemaVersion,
        String detectedSchemaFamily,
        java.util.List<String> pendingMigrations,
        String migrationErrorCode,
        String migrationErrorMessage
) {
}


