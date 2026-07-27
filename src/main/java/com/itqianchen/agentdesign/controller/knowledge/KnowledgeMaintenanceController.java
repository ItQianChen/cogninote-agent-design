package com.itqianchen.agentdesign.controller.knowledge;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderEnabledRequest;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderImportRequest;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeMaintenanceQueueResponse;
import com.itqianchen.agentdesign.service.knowledge.KnowledgeMaintenanceQueueService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识库维护任务控制器。
 *
 * <p>该接口只创建、查询和取消维护任务；真正的导入、同步、重建和删除由队列服务串行调度。</p>
 */
@RestController
@RequestMapping("/api/knowledge-maintenance/runs")
public class KnowledgeMaintenanceController {

    private final KnowledgeMaintenanceQueueService queueService;

    public KnowledgeMaintenanceController(KnowledgeMaintenanceQueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/rebuild-index")
    public ApiResponse<KnowledgeFolderRunResponse> rebuildIndex() {
        return ApiResponse.ok(queueService.enqueueRebuildAllIndex());
    }

    @PostMapping("/repair-index")
    public ApiResponse<KnowledgeFolderRunResponse> repairIndex() {
        return ApiResponse.ok(queueService.enqueueRepairAllIndex());
    }

    @PostMapping("/import-folder")
    public ApiResponse<KnowledgeFolderRunResponse> importFolder(
            @Valid @RequestBody KnowledgeFolderImportRequest request
    ) {
        return ApiResponse.ok(queueService.enqueueImport(request.folderPath(), request.recursiveOrDefault()));
    }

    @PostMapping("/folders/{id}/sync")
    public ApiResponse<KnowledgeFolderRunResponse> syncFolder(@PathVariable String id) {
        return ApiResponse.ok(queueService.enqueueFolderSync(id));
    }

    @PostMapping("/folders/{id}/reparse")
    public ApiResponse<KnowledgeFolderRunResponse> reparseFolder(@PathVariable String id) {
        return ApiResponse.ok(queueService.enqueueFolderReparse(id));
    }

    @PostMapping("/folders/{id}/rebuild")
    public ApiResponse<KnowledgeFolderRunResponse> rebuildFolder(@PathVariable String id) {
        return ApiResponse.ok(queueService.enqueueFolderRebuild(id));
    }

    @PostMapping("/folders/{id}/repair-index")
    public ApiResponse<KnowledgeFolderRunResponse> repairFolderIndex(@PathVariable String id) {
        return ApiResponse.ok(queueService.enqueueFolderRepairIndex(id));
    }

    @PostMapping("/folders/{id}/enabled")
    public ApiResponse<KnowledgeFolderRunResponse> setEnabled(
            @PathVariable String id,
            @Valid @RequestBody KnowledgeFolderEnabledRequest request
    ) {
        return ApiResponse.ok(queueService.enqueueFolderEnabled(id, request.enabled()));
    }

    @PostMapping("/folders/{id}/delete")
    public ApiResponse<KnowledgeFolderRunResponse> deleteFolder(@PathVariable String id) {
        return ApiResponse.ok(queueService.enqueueFolderDelete(id));
    }

    @GetMapping("/queue")
    public ApiResponse<KnowledgeMaintenanceQueueResponse> queue() {
        return ApiResponse.ok(queueService.queue());
    }

    @GetMapping("/{runId}")
    public ApiResponse<KnowledgeFolderRunResponse> run(@PathVariable String runId) {
        return ApiResponse.ok(queueService.getRun(runId));
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId) {
        return queueService.subscribe(runId);
    }

    @PostMapping("/{runId}/cancel")
    public ApiResponse<Boolean> cancel(@PathVariable String runId) {
        return ApiResponse.ok(queueService.cancel(runId));
    }

    @PostMapping("/{runId}/retry")
    public ApiResponse<KnowledgeFolderRunResponse> retry(@PathVariable String runId) {
        return ApiResponse.ok(queueService.retry(runId));
    }
}
