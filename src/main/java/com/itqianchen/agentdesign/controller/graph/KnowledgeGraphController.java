package com.itqianchen.agentdesign.controller.graph;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphEvidenceResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphRebuildRequest;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphRunResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphStatusResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphViewResponse;
import com.itqianchen.agentdesign.service.graph.KnowledgeGraphService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识图谱 HTTP 接口。
 */
@RestController
@RequestMapping("/api/knowledge-graphs")
public class KnowledgeGraphController {

    private final KnowledgeGraphService knowledgeGraphService;

    public KnowledgeGraphController(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @PostMapping("/rebuild")
    public ApiResponse<KnowledgeGraphRunResponse> rebuild(@Valid @RequestBody KnowledgeGraphRebuildRequest request) {
        return ApiResponse.ok(knowledgeGraphService.rebuild(request.scopeType(), request.scopeId()));
    }

    @GetMapping("/status")
    public ApiResponse<KnowledgeGraphStatusResponse> status(
            @RequestParam String scopeType,
            @RequestParam(required = false) String scopeId
    ) {
        return ApiResponse.ok(knowledgeGraphService.status(scopeType, scopeId));
    }

    @GetMapping("/view")
    public ApiResponse<KnowledgeGraphViewResponse> view(
            @RequestParam String scopeType,
            @RequestParam(required = false) String scopeId,
            @RequestParam String viewType
    ) {
        return ApiResponse.ok(knowledgeGraphService.view(scopeType, scopeId, viewType));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<KnowledgeGraphRunResponse> run(@PathVariable String runId) {
        return ApiResponse.ok(knowledgeGraphService.getRun(runId));
    }

    @GetMapping(path = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId) {
        return knowledgeGraphService.subscribe(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ApiResponse<Boolean> cancel(@PathVariable String runId) {
        return ApiResponse.ok(knowledgeGraphService.cancel(runId));
    }

    @GetMapping("/nodes/{nodeId}/evidence")
    public ApiResponse<List<KnowledgeGraphEvidenceResponse>> nodeEvidence(@PathVariable String nodeId) {
        return ApiResponse.ok(knowledgeGraphService.nodeEvidence(nodeId));
    }

    @GetMapping("/edges/{edgeId}/evidence")
    public ApiResponse<List<KnowledgeGraphEvidenceResponse>> edgeEvidence(@PathVariable String edgeId) {
        return ApiResponse.ok(knowledgeGraphService.edgeEvidence(edgeId));
    }
}
