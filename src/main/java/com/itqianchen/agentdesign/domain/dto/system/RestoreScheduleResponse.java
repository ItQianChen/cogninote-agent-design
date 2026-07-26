package com.itqianchen.agentdesign.domain.dto.system;

/** 已写入 pending marker、等待桌面壳重启的恢复任务。 */
public record RestoreScheduleResponse(String restoreId, boolean restartRequired) {
}
