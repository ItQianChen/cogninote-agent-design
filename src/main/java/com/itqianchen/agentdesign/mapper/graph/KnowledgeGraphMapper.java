package com.itqianchen.agentdesign.mapper.graph;

import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphChunkExtraction;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEvidence;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphRun;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 知识图谱 MyBatis Mapper。
 * <p>所有图谱数据仍落在 SQLite；外键级联不可用，因此删除操作必须显式声明。</p>
 */
public interface KnowledgeGraphMapper {

    void insertRun(KnowledgeGraphRun run);

    List<KnowledgeGraphRun> findRunById(@Param("id") String id);

    List<KnowledgeGraphRun> findActiveRun(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    List<KnowledgeGraphRun> findLatestRunForScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void markRunStarted(
            @Param("id") String id,
            @Param("totalChunkCount") int totalChunkCount,
            @Param("startedAt") long startedAt,
            @Param("updatedAt") long updatedAt
    );

    void updateRunProgress(
            @Param("id") String id,
            @Param("processedChunkCount") int processedChunkCount,
            @Param("skippedChunkCount") int skippedChunkCount,
            @Param("failedChunkCount") int failedChunkCount,
            @Param("updatedAt") long updatedAt
    );

    void markRunCompleted(
            @Param("id") String id,
            @Param("extractedNodeCount") int extractedNodeCount,
            @Param("extractedEdgeCount") int extractedEdgeCount,
            @Param("completedAt") long completedAt,
            @Param("updatedAt") long updatedAt
    );

    void markRunFailed(
            @Param("id") String id,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt,
            @Param("updatedAt") long updatedAt
    );

    void markRunCancelled(
            @Param("id") String id,
            @Param("completedAt") long completedAt,
            @Param("updatedAt") long updatedAt
    );

    void failOrphanRuns(
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt,
            @Param("updatedAt") long updatedAt
    );

    List<KnowledgeGraphChunkExtraction> findExtractionByChunkId(@Param("chunkId") String chunkId);

    List<KnowledgeGraphChunkExtraction> findExtractionsByChunkIds(@Param("chunkIds") List<String> chunkIds);

    void upsertChunkExtraction(KnowledgeGraphChunkExtraction extraction);

    void deleteOrphanChunkExtractions();

    void deleteChunkExtractionsByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    void deleteEvidenceByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void deleteViewsByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void deleteEdgesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void deleteNodesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void deleteRunsByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    void insertNode(KnowledgeGraphNode node);

    void insertEdge(KnowledgeGraphEdge edge);

    void insertEvidence(KnowledgeGraphEvidence evidence);

    void insertView(KnowledgeGraphView view);

    List<KnowledgeGraphNode> findNodesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    List<KnowledgeGraphEdge> findEdgesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    List<KnowledgeGraphView> findView(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("viewType") String viewType
    );

    int countNodesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    int countEdgesByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    List<KnowledgeGraphEvidenceDetailRow> findEvidenceByNodeId(@Param("nodeId") String nodeId);

    List<KnowledgeGraphEvidenceDetailRow> findEvidenceByEdgeId(@Param("edgeId") String edgeId);

    List<KnowledgeGraphEvidenceDetailRow> findNodeEvidenceByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );
}
