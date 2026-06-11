package com.itqianchen.agentdesign.repository.graph;

import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphChunkExtraction;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEvidence;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphRun;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphEvidenceDetailRow;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 知识图谱仓储。
 * <p>图谱事实和派生视图都存 SQLite；这里集中处理 scope NULL 规则和显式级联删除。</p>
 */
@Repository
public class KnowledgeGraphRepository {

    private final KnowledgeGraphMapper mapper;

    public KnowledgeGraphRepository(KnowledgeGraphMapper mapper) {
        this.mapper = mapper;
    }

    public void insertRun(KnowledgeGraphRun run) {
        mapper.insertRun(run);
    }

    public Optional<KnowledgeGraphRun> findRunById(String id) {
        return mapper.findRunById(id).stream().findFirst();
    }

    public Optional<KnowledgeGraphRun> findActiveRun(KnowledgeGraphScope scope) {
        return mapper.findActiveRun(scope.scopeType().name(), scope.normalizedScopeId()).stream().findFirst();
    }

    public Optional<KnowledgeGraphRun> findLatestRunForScope(KnowledgeGraphScope scope) {
        return mapper.findLatestRunForScope(scope.scopeType().name(), scope.normalizedScopeId()).stream().findFirst();
    }

    public void markRunStarted(String id, int totalChunkCount, long now) {
        mapper.markRunStarted(id, totalChunkCount, now, now);
    }

    public void updateRunProgress(
            String id,
            int processedChunkCount,
            int skippedChunkCount,
            int failedChunkCount,
            long now
    ) {
        mapper.updateRunProgress(id, processedChunkCount, skippedChunkCount, failedChunkCount, now);
    }

    public void markRunCompleted(String id, int nodeCount, int edgeCount, long now) {
        mapper.markRunCompleted(id, nodeCount, edgeCount, now, now);
    }

    public void markRunFailed(String id, String errorMessage, long now) {
        mapper.markRunFailed(id, errorMessage, now, now);
    }

    public void markRunCancelled(String id, long now) {
        mapper.markRunCancelled(id, now, now);
    }

    public void failOrphanRuns(String errorMessage, long now) {
        mapper.failOrphanRuns(errorMessage, now, now);
    }

    public Optional<KnowledgeGraphChunkExtraction> findExtractionByChunkId(String chunkId) {
        return mapper.findExtractionByChunkId(chunkId).stream().findFirst();
    }

    public List<KnowledgeGraphChunkExtraction> findExtractionsByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return mapper.findExtractionsByChunkIds(chunkIds);
    }

    public void upsertChunkExtraction(KnowledgeGraphChunkExtraction extraction) {
        mapper.upsertChunkExtraction(extraction);
    }

    public void deleteOrphanChunkExtractions() {
        mapper.deleteOrphanChunkExtractions();
    }

    public void deleteByKnowledgeFolderId(String knowledgeFolderId) {
        /*
         * SQLite foreign_keys 未启用，删除必须从 evidence 开始显式收敛。
         * 目录删除发生在文档/chunk 删除前，因此缓存清理还能通过 documents 表定位。
         */
        deleteScopeDerivedGraph("KNOWLEDGE_FOLDER", knowledgeFolderId);
        mapper.deleteRunsByScope("KNOWLEDGE_FOLDER", knowledgeFolderId);
        mapper.deleteChunkExtractionsByKnowledgeFolderId(knowledgeFolderId);
    }

    public void deleteScopeDerivedGraph(KnowledgeGraphScope scope) {
        deleteScopeDerivedGraph(scope.scopeType().name(), scope.normalizedScopeId());
    }

    private void deleteScopeDerivedGraph(String scopeType, String scopeId) {
        mapper.deleteEvidenceByScope(scopeType, scopeId);
        mapper.deleteViewsByScope(scopeType, scopeId);
        mapper.deleteEdgesByScope(scopeType, scopeId);
        mapper.deleteNodesByScope(scopeType, scopeId);
    }

    public void insertNode(KnowledgeGraphNode node) {
        mapper.insertNode(node);
    }

    public void insertEdge(KnowledgeGraphEdge edge) {
        mapper.insertEdge(edge);
    }

    public void insertEvidence(KnowledgeGraphEvidence evidence) {
        mapper.insertEvidence(evidence);
    }

    public void insertView(KnowledgeGraphView view) {
        mapper.insertView(view);
    }

    public List<KnowledgeGraphNode> findNodesByScope(KnowledgeGraphScope scope) {
        return mapper.findNodesByScope(scope.scopeType().name(), scope.normalizedScopeId());
    }

    public List<KnowledgeGraphEdge> findEdgesByScope(KnowledgeGraphScope scope) {
        return mapper.findEdgesByScope(scope.scopeType().name(), scope.normalizedScopeId());
    }

    public Optional<KnowledgeGraphView> findView(KnowledgeGraphScope scope, String viewType) {
        return mapper.findView(scope.scopeType().name(), scope.normalizedScopeId(), viewType).stream().findFirst();
    }

    public int countNodesByScope(KnowledgeGraphScope scope) {
        return mapper.countNodesByScope(scope.scopeType().name(), scope.normalizedScopeId());
    }

    public int countEdgesByScope(KnowledgeGraphScope scope) {
        return mapper.countEdgesByScope(scope.scopeType().name(), scope.normalizedScopeId());
    }

    public List<KnowledgeGraphEvidenceDetailRow> findEvidenceByNodeId(String nodeId) {
        return mapper.findEvidenceByNodeId(nodeId);
    }

    public List<KnowledgeGraphEvidenceDetailRow> findEvidenceByEdgeId(String edgeId) {
        return mapper.findEvidenceByEdgeId(edgeId);
    }

    public List<KnowledgeGraphEvidenceDetailRow> findNodeEvidenceByScope(KnowledgeGraphScope scope) {
        return mapper.findNodeEvidenceByScope(scope.scopeType().name(), scope.normalizedScopeId());
    }
}
