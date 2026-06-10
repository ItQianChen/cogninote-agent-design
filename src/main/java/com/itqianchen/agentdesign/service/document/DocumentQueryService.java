package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.dto.document.DocumentChunkResponse;
import com.itqianchen.agentdesign.dto.document.DocumentSummaryResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document Query 服务 承载 文档管理 的应用服务流程。
 * <p>这里集中编排仓储、模型运行时和 DTO 映射，保证控制器保持轻量。</p>
 */
@Service
public class DocumentQueryService {

    private final DocumentRepository documentRepository;

    /**
     * 注入 DocumentQueryService 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public DocumentQueryService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /**
     * 查询 文档管理 列表。
     * <p>返回值面向上层展示或接口响应，不暴露底层存储细节。</p>
     */
    @Transactional(readOnly = true)
    public List<DocumentSummaryResponse> listDocuments() {
        return documentRepository.findAllOrderByUpdatedAtDesc()
                .stream()
                .map(DocumentSummaryResponse::from)
                .toList();
    }

    /**
     * 查询 文档片段详情。
     * <p>来源预览必须读取后端存储的完整 chunk 内容，而不是聊天消息里的摘要快照。</p>
     */
    @Transactional(readOnly = true)
    public DocumentChunkResponse getChunk(String chunkId) {
        return documentRepository.findStoredChunkById(chunkId)
                .map(DocumentChunkResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Document chunk not found: " + chunkId));
    }
}
