package com.itqianchen.agentdesign.controller.document;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.dto.document.DocumentChunkResponse;
import com.itqianchen.agentdesign.dto.document.DocumentSummaryResponse;
import com.itqianchen.agentdesign.dto.document.IngestDocumentsRequest;
import com.itqianchen.agentdesign.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.service.document.DocumentManagementService;
import com.itqianchen.agentdesign.service.document.DocumentQueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document 控制器 暴露 文档管理 的 HTTP 接口。
 * <p>控制器只负责请求参数、响应包装和服务层委派，避免承载业务细节。</p>
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final DocumentManagementService documentManagementService;
    private final DocumentQueryService documentQueryService;

    /**
     * 注入 DocumentController 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public DocumentController(
            DocumentIngestionService ingestionService,
            DocumentManagementService documentManagementService,
            DocumentQueryService documentQueryService
    ) {
        this.ingestionService = ingestionService;
        this.documentManagementService = documentManagementService;
        this.documentQueryService = documentQueryService;
    }

    /**
     * 查询 文档管理 列表。
     * <p>返回值面向上层展示或接口响应，不暴露底层存储细节。</p>
     */
    @GetMapping
    public ApiResponse<List<DocumentSummaryResponse>> listDocuments() {
        return ApiResponse.ok(documentQueryService.listDocuments());
    }

    /**
     * 查询 文档片段详情。
     * <p>该接口供来源预览弹窗读取完整片段内容，不改变聊天消息中的来源快照。</p>
     */
    @GetMapping("/chunks/{chunkId}")
    public ApiResponse<DocumentChunkResponse> getChunk(@PathVariable String chunkId) {
        return ApiResponse.ok(documentQueryService.getChunk(chunkId));
    }

    /**
     * 执行 文档管理 中的 ingest 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @PostMapping("/ingest")
    public ApiResponse<IngestDocumentsResponse> ingest(@Valid @RequestBody IngestDocumentsRequest request) {
        return ApiResponse.ok(ingestionService.ingestFolder(request.folderPath(), request.recursiveOrDefault()));
    }

    /**
     * 删除 delete Document 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        if (!documentManagementService.deleteDocument(id)) {
            throw new ResourceNotFoundException("Document not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }
}


