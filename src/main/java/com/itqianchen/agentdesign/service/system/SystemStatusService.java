package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.dto.system.SystemStatusResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SystemStatusService {

    private static final String STATUS_UP = "UP";

    private final AppStorageInitializer storageInitializer;
    private final String version;
    private final boolean desktopMode;

    public SystemStatusService(
            AppStorageInitializer storageInitializer,
            @Value("${app.version:${project.version:0.0.1-SNAPSHOT}}") String version
    ) {
        this.storageInitializer = storageInitializer;
        this.version = version;
        this.desktopMode = Boolean.parseBoolean(System.getenv().getOrDefault("COGNINOTE_DESKTOP", "false"));
    }

    public SystemStatusResponse status() {
        return new SystemStatusResponse(
                "CogniNote Agent",
                version,
                STATUS_UP,
                storageInitializer.appStorage().baseDir().toString(),
                desktopMode
        );
    }
}
