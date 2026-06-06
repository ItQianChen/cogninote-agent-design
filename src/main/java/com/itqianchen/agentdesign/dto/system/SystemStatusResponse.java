package com.itqianchen.agentdesign.dto.system;

public record SystemStatusResponse(
        String appName,
        String version,
        String status,
        String dataDir,
        boolean desktopMode
) {
}


