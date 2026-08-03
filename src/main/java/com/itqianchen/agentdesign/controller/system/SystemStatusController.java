package com.itqianchen.agentdesign.controller.system;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.dto.system.SystemStatusResponse;
import com.itqianchen.agentdesign.domain.dto.system.MigrationRecoveryFileResponse;
import com.itqianchen.agentdesign.service.system.MigrationInspectionResult;
import com.itqianchen.agentdesign.service.system.MigrationRecoveryService;
import com.itqianchen.agentdesign.service.system.SystemStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System Status 控制器 暴露 系统状态 的 HTTP 接口。
 * <p>控制器只负责请求参数、响应包装和服务层委派，避免承载业务细节。</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    private final SystemStatusService systemStatusService;
    private final MigrationRecoveryService migrationRecoveryService;

    /**
     * 注入系统状态服务。
     *
     * @param systemStatusService 系统状态聚合服务
     */
    public SystemStatusController(
            SystemStatusService systemStatusService,
            MigrationRecoveryService migrationRecoveryService
    ) {
        this.systemStatusService = systemStatusService;
        this.migrationRecoveryService = migrationRecoveryService;
    }

    /**
     * 读取后端运行状态。
     *
     * <p>该接口用于前端启动检查和设置页诊断，不改变任何本地状态。</p>
     *
     * @return 系统状态响应
     */
    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        return ApiResponse.ok(systemStatusService.status());
    }

    @GetMapping("/migration/status")
    public ApiResponse<MigrationInspectionResult> migrationStatus() {
        return ApiResponse.ok(migrationRecoveryService.status());
    }

    @PostMapping("/migration/backup")
    public ApiResponse<MigrationRecoveryFileResponse> migrationBackup() {
        return ApiResponse.ok(migrationRecoveryService.backup());
    }

    @PostMapping("/migration/export")
    public ApiResponse<MigrationRecoveryFileResponse> migrationExport() {
        return ApiResponse.ok(migrationRecoveryService.exportDiagnostics());
    }

    @PostMapping("/migration/retry")
    public ApiResponse<MigrationInspectionResult> migrationRetry() {
        return ApiResponse.ok(migrationRecoveryService.retry());
    }
}


