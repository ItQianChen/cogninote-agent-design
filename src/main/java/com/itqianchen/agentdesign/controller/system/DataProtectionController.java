package com.itqianchen.agentdesign.controller.system;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.dto.system.BackupCreateResponse;
import com.itqianchen.agentdesign.domain.dto.system.DataProtectionStatusResponse;
import com.itqianchen.agentdesign.domain.dto.system.RestorePreflightRequest;
import com.itqianchen.agentdesign.domain.dto.system.RestoreScheduleResponse;
import com.itqianchen.agentdesign.domain.dto.system.RestoreStatusResponse;
import com.itqianchen.agentdesign.service.system.DataProtectionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本机备份生成、恢复预检和跨重启调度 API。 */
@RestController
@RequestMapping("/api/system")
public class DataProtectionController {

    private final DataProtectionService dataProtectionService;

    public DataProtectionController(DataProtectionService dataProtectionService) {
        this.dataProtectionService = dataProtectionService;
    }

    @GetMapping("/data-protection/status")
    public ApiResponse<DataProtectionStatusResponse> status() {
        return ApiResponse.ok(dataProtectionService.status());
    }

    @PostMapping("/backups")
    public ApiResponse<BackupCreateResponse> createBackup() {
        return ApiResponse.ok(dataProtectionService.createBackup());
    }

    @PostMapping("/restores/preflight")
    public ApiResponse<RestoreStatusResponse> preflight(@Valid @RequestBody RestorePreflightRequest request) {
        return ApiResponse.ok(dataProtectionService.preflight(request.importId()));
    }

    @PostMapping("/restores/{restoreId}/schedule")
    public ApiResponse<RestoreScheduleResponse> schedule(@PathVariable String restoreId) {
        return ApiResponse.ok(dataProtectionService.scheduleRestore(restoreId));
    }

    @DeleteMapping("/restores/{restoreId}")
    public ApiResponse<RestoreStatusResponse> discard(@PathVariable String restoreId) {
        return ApiResponse.ok(dataProtectionService.discardRestore(restoreId));
    }

    @GetMapping("/restores/{restoreId}")
    public ApiResponse<RestoreStatusResponse> restoreStatus(@PathVariable String restoreId) {
        return ApiResponse.ok(dataProtectionService.restoreStatus(restoreId));
    }
}
