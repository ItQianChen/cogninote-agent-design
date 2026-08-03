package com.itqianchen.agentdesign.domain.dto.system;

/** 恢复模式生成的原始数据库或诊断包位置。 */
public record MigrationRecoveryFileResponse(String path, String kind, long sizeBytes) {
}
