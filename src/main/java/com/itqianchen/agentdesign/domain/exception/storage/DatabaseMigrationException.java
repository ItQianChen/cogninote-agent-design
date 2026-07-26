package com.itqianchen.agentdesign.domain.exception.storage;

/**
 * 数据库版本无法安全识别、迁移或回滚时抛出的启动异常。
 */
public class DatabaseMigrationException extends RuntimeException {

    public DatabaseMigrationException(String message) {
        super(message);
    }

    public DatabaseMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
