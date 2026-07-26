package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import com.itqianchen.agentdesign.domain.exception.storage.StorageInitializationException;
import com.itqianchen.agentdesign.domain.properties.storage.StorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 计算并创建 CogniNote 的本地数据目录。
 *
 * <p>桌面版默认落在系统应用数据目录，测试或开发环境可通过配置覆盖 baseDir/databasePath。
 * 初始化必须幂等，不能清理用户已有数据库、索引或日志。</p>
 */
@Component
public class AppStorageInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final String APP_DATA_ENV = "APPDATA";
    private static final String DEFAULT_APP_DIR = "CogniNote";

    private final StorageProperties storageProperties;
    private final AppStorage appStorage;

    /**
     * 计算应用存储路径。
     *
     * @param storageProperties 存储配置
     */
    public AppStorageInitializer(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        Path baseDir = resolveBaseDir(storageProperties.baseDir());
        Path dataDir = baseDir.resolve("data");
        Path dataProtectionDir = baseDir.resolve("data-protection");
        this.appStorage = new AppStorage(
                baseDir,
                baseDir.resolve("config"),
                dataDir,
                resolveDatabasePath(storageProperties.databasePath(), dataDir.resolve("cogninote.db")),
                baseDir.resolve("index").resolve("lucene"),
                baseDir.resolve("logs"),
                dataProtectionDir,
                dataProtectionDir.resolve("exports"),
                dataProtectionDir.resolve("restore-inbox"),
                dataProtectionDir.resolve("restore-work"),
                dataProtectionDir.resolve("internal-backups")
        );
    }

    /**
     * 提供启动期解析完成的应用存储路径。
     *
     * <p>返回对象只描述路径，不代表目录已经创建；需要写入文件前应先经过 ensureInitialized。</p>
     *
     * @return 应用存储路径
     */
    public AppStorage appStorage() {
        return appStorage;
    }

    /**
     * 应用就绪后确保目录存在。
     *
     * @param event Spring Boot 应用就绪事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ensureInitialized();
    }

    /**
     * 幂等创建应用目录。
     */
    public void ensureInitialized() {
        createDirectory(appStorage.baseDir());
        createDirectory(appStorage.configDir());
        createDirectory(appStorage.dataDir());
        createDirectory(appStorage.databasePath().getParent());
        createDirectory(appStorage.luceneIndexDir());
        createDirectory(appStorage.logsDir());
        createDirectory(appStorage.dataProtectionDir());
        createDirectory(appStorage.backupExportDir());
        createDirectory(appStorage.restoreInboxDir());
        createDirectory(appStorage.restoreWorkDir());
        createDirectory(appStorage.internalBackupDir());
    }

    /**
     * 解析应用基础目录。
     *
     * @param configuredBaseDir 配置覆盖值
     * @return 规范化基础目录
     */
    private Path resolveBaseDir(String configuredBaseDir) {
        if (StringUtils.hasText(configuredBaseDir)) {
            return Path.of(configuredBaseDir).toAbsolutePath().normalize();
        }

        String appData = System.getenv(APP_DATA_ENV);
        if (StringUtils.hasText(appData)) {
            return Path.of(appData, DEFAULT_APP_DIR).toAbsolutePath().normalize();
        }

        // 非 Windows 开发环境不一定有 APPDATA，保留 home 目录兜底方便测试和跨平台开发。
        return Path.of(System.getProperty("user.home"), "." + DEFAULT_APP_DIR.toLowerCase()).toAbsolutePath().normalize();
    }

    /**
     * 解析 SQLite 数据库路径。
     *
     * @param configuredDatabasePath 配置覆盖值
     * @param defaultDatabasePath 默认数据库路径
     * @return 规范化数据库路径
     */
    private Path resolveDatabasePath(String configuredDatabasePath, Path defaultDatabasePath) {
        if (StringUtils.hasText(configuredDatabasePath)) {
            // databasePath 允许单独覆盖，方便测试把数据库放到临时目录而不影响其他存储路径。
            return Path.of(configuredDatabasePath).toAbsolutePath().normalize();
        }

        return defaultDatabasePath.toAbsolutePath().normalize();
    }

    /**
     * 创建目录。
     *
     * @param directory 目录路径
     */
    private void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            throw new StorageInitializationException("Failed to create application directory: " + directory, ex);
        }
    }
}


