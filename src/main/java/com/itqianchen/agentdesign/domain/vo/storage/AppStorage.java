package com.itqianchen.agentdesign.domain.vo.storage;

import java.nio.file.Path;

/**
 * 应用运行时使用的本地存储目录集合。
 *
 * <p>这些路径由启动初始化统一创建，后端、Lucene 和 Tauri 桌面端必须共享同一 baseDir，
 * 否则数据库、索引和日志会落到不同位置。</p>
 */
public record AppStorage(
        Path baseDir,
        Path configDir,
        Path dataDir,
        Path databasePath,
        Path luceneIndexDir,
        Path logsDir,
        Path dataProtectionDir,
        Path backupExportDir,
        Path restoreInboxDir,
        Path restoreWorkDir,
        Path internalBackupDir
) {
}


