package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.dto.system.SystemStatusResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 后端健康状态和桌面运行环境信息。
 *
 * <p>该响应会被前端工作区导航频繁读取，不能触发昂贵的文件扫描或模型连接测试。</p>
 */
@Service
public class SystemStatusService {

    private static final String STATUS_UP = "UP";
    private static final String DISPLAY_APP_NAME = "知记空间";
    private static final String FALLBACK_VERSION = "dev";

    private final AppStorageInitializer storageInitializer;
    private final String version;
    private final boolean desktopMode;

    /**
     * 注入系统状态依赖并解析运行模式。
     *
     * @param storageInitializer 应用存储初始化器
     * @param configuredVersion 配置覆盖版本号
     * @param buildPropertiesProvider Maven build-info 提供器
     */
    public SystemStatusService(
            AppStorageInitializer storageInitializer,
            @Value("${app.version:}") String configuredVersion,
            @Value("${app.desktop.enabled:false}") boolean desktopMode,
            ObjectProvider<BuildProperties> buildPropertiesProvider
    ) {
        this.storageInitializer = storageInitializer;
        this.version = resolveVersion(configuredVersion, buildPropertiesProvider.getIfAvailable());
        this.desktopMode = desktopMode;
    }

    /**
     * 读取轻量系统状态。
     *
     * @return 系统状态响应
     */
    public SystemStatusResponse status() {
        return new SystemStatusResponse(
                DISPLAY_APP_NAME,
                version,
                STATUS_UP,
                storageInitializer.appStorage().baseDir().toString(),
                desktopMode
        );
    }

    /**
     * 解析对外展示的后端版本。
     * <p>优先允许部署环境用 app.version 显式覆盖；默认读取 Maven 打包生成的 build-info，避免运行时读取不到
     * project.version 时回落到误导性的 SNAPSHOT 兜底值。</p>
     *
     * @param configuredVersion 配置覆盖版本号
     * @param buildProperties 打包生成的 build-info
     * @return 展示版本号
     */
    static String resolveVersion(String configuredVersion, BuildProperties buildProperties) {
        if (StringUtils.hasText(configuredVersion)) {
            return configuredVersion.trim();
        }
        if (buildProperties != null && StringUtils.hasText(buildProperties.getVersion())) {
            return buildProperties.getVersion();
        }
        return FALLBACK_VERSION;
    }
}
