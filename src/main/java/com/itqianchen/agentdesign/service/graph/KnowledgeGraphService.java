package com.itqianchen.agentdesign.service.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphException;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphRun;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphRunStatus;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphViewType;
import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphEvidenceResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphRunResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphStatusResponse;
import com.itqianchen.agentdesign.dto.graph.KnowledgeGraphViewResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识图谱应用服务。
 * <p>负责 run 生命周期、scope 解析、后台任务编排和前端查询 API。</p>
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final KnowledgeGraphRepository graphRepository;
    private final KnowledgeFolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final ModelConfigService modelConfigService;
    private final GraphExtractionService extractionService;
    private final GraphMergeService mergeService;
    private final KnowledgeGraphRunPublisher publisher;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    public KnowledgeGraphService(
            KnowledgeGraphRepository graphRepository,
            KnowledgeFolderRepository folderRepository,
            DocumentRepository documentRepository,
            ModelConfigService modelConfigService,
            GraphExtractionService extractionService,
            GraphMergeService mergeService,
            KnowledgeGraphRunPublisher publisher,
            TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this.graphRepository = graphRepository;
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.modelConfigService = modelConfigService;
        this.extractionService = extractionService;
        this.mergeService = mergeService;
        this.publisher = publisher;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    public synchronized KnowledgeGraphRunResponse rebuild(String scopeType, String scopeId) {
        KnowledgeGraphScope scope = resolveScope(scopeType, scopeId);
        return graphRepository.findActiveRun(scope)
                .map(KnowledgeGraphRunResponse::from)
                .orElseGet(() -> createAndStartRun(scope));
    }

    public KnowledgeGraphRunResponse getRun(String runId) {
        return graphRepository.findRunById(runId)
                .map(KnowledgeGraphRunResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge graph run not found: " + runId));
    }

    public SseEmitter subscribe(String runId) {
        KnowledgeGraphRunResponse snapshot = getRun(runId);
        boolean terminal = !"QUEUED".equals(snapshot.status()) && !"RUNNING".equals(snapshot.status());
        return publisher.subscribe(runId, snapshot, terminal);
    }

    public boolean cancel(String runId) {
        KnowledgeGraphRun run = graphRepository.findRunById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge graph run not found: " + runId));
        if (run.status() != KnowledgeGraphRunStatus.QUEUED && run.status() != KnowledgeGraphRunStatus.RUNNING) {
            return false;
        }
        publisher.cancel(runId);
        return true;
    }

    public KnowledgeGraphStatusResponse status(String scopeType, String scopeId) {
        KnowledgeGraphScope scope = resolveScope(scopeType, scopeId);
        KnowledgeGraphView mindmap = graphRepository.findView(scope, KnowledgeGraphViewType.MINDMAP.name()).orElse(null);
        KnowledgeGraphView graph = graphRepository.findView(scope, KnowledgeGraphViewType.GRAPH.name()).orElse(null);
        Long generatedAt = maxUpdatedAt(mindmap, graph);
        return new KnowledgeGraphStatusResponse(
                scope.scopeType().name(),
                scope.normalizedScopeId(),
                scope.displayName(),
                graphRepository.findLatestRunForScope(scope).map(KnowledgeGraphRunResponse::from).orElse(null),
                graphRepository.countNodesByScope(scope),
                graphRepository.countEdgesByScope(scope),
                mindmap != null,
                graph != null,
                generatedAt
        );
    }

    public KnowledgeGraphViewResponse view(String scopeType, String scopeId, String viewType) {
        KnowledgeGraphScope scope = resolveScope(scopeType, scopeId);
        KnowledgeGraphViewType normalizedViewType = parseViewType(viewType);
        KnowledgeGraphView view = graphRepository.findView(scope, normalizedViewType.name())
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge graph view not found: " + normalizedViewType));
        try {
            JsonNode payload = objectMapper.readTree(view.payloadJson());
            return KnowledgeGraphViewResponse.from(view, payload);
        } catch (JsonProcessingException ex) {
            throw new KnowledgeGraphException("Knowledge graph view payload is corrupted", ex);
        }
    }

    public List<KnowledgeGraphEvidenceResponse> nodeEvidence(String nodeId) {
        return graphRepository.findEvidenceByNodeId(nodeId).stream()
                .map(KnowledgeGraphEvidenceResponse::from)
                .toList();
    }

    public List<KnowledgeGraphEvidenceResponse> edgeEvidence(String edgeId) {
        return graphRepository.findEvidenceByEdgeId(edgeId).stream()
                .map(KnowledgeGraphEvidenceResponse::from)
                .toList();
    }

    private KnowledgeGraphRunResponse createAndStartRun(KnowledgeGraphScope scope) {
        ModelConfig chatConfigSnapshot = modelConfigService.activeChatOrDefault();
        long now = System.currentTimeMillis();
        KnowledgeGraphRun run = new KnowledgeGraphRun(
                UUID.randomUUID().toString(),
                scope.scopeType(),
                scope.normalizedScopeId(),
                KnowledgeGraphRunStatus.QUEUED,
                chatConfigSnapshot.id(),
                extractionService.promptVersion(),
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                now,
                now
        );
        graphRepository.insertRun(run);
        try {
            taskExecutor.execute(() -> runGraphTask(run.id(), scope));
        } catch (RuntimeException ex) {
            graphRepository.markRunFailed(run.id(), "Failed to start graph run: " + ex.getMessage(), System.currentTimeMillis());
            throw ex;
        }
        return KnowledgeGraphRunResponse.from(run);
    }

    private void runGraphTask(String runId, KnowledgeGraphScope scope) {
        publisher.clearCancellation(runId);
        try {
            graphRepository.deleteOrphanChunkExtractions();
            List<IndexedDocument> documents = documentsForScope(scope);
            int totalChunks = extractionService.countChunks(documents);
            long startedAt = System.currentTimeMillis();
            graphRepository.markRunStarted(runId, totalChunks, startedAt);
            publisher.publishStarted(runId, new KnowledgeGraphRunProgress(
                    runId,
                    KnowledgeGraphRunStatus.RUNNING.name(),
                    "EXTRACTING",
                    totalChunks,
                    0,
                    0,
                    0
            ));

            ModelConfig chatConfig = modelConfigService.requireActiveChatConfigured();
            GraphExtractionResult extractionResult = extractionService.extract(runId, documents, chatConfig);
            if (extractionResult.cancelled() || publisher.isCancelled(runId)) {
                long now = System.currentTimeMillis();
                graphRepository.markRunCancelled(runId, now);
                publisher.publishCancelled(runId, Map.of("runId", runId, "status", KnowledgeGraphRunStatus.CANCELLED.name()));
                return;
            }

            publisher.publishProgress(runId, new KnowledgeGraphRunProgress(
                    runId,
                    KnowledgeGraphRunStatus.RUNNING.name(),
                    "MERGING",
                    totalChunks,
                    extractionResult.processedChunkCount(),
                    extractionResult.skippedChunkCount(),
                    extractionResult.failedChunkCount()
            ));
            GraphMergeResult mergeResult = mergeService.merge(scope, runId, documents, chatConfig.id());
            long completedAt = System.currentTimeMillis();
            graphRepository.markRunCompleted(runId, mergeResult.nodeCount(), mergeResult.edgeCount(), completedAt);
            publisher.publishViewReady(runId, KnowledgeGraphViewType.MINDMAP.name());
            publisher.publishViewReady(runId, KnowledgeGraphViewType.GRAPH.name());
            publisher.publishCompleted(runId, Map.of(
                    "runId", runId,
                    "status", KnowledgeGraphRunStatus.COMPLETED.name(),
                    "nodeCount", mergeResult.nodeCount(),
                    "edgeCount", mergeResult.edgeCount()
            ));
        } catch (RuntimeException ex) {
            long now = System.currentTimeMillis();
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            graphRepository.markRunFailed(runId, message, now);
            publisher.publishFailed(runId, Map.of(
                    "runId", runId,
                    "status", KnowledgeGraphRunStatus.FAILED.name(),
                    "message", message
            ));
            log.warn("knowledge_graph_run_failed runId={} scopeType={} scopeId={} reason={}",
                    runId,
                    scope.scopeType(),
                    scope.normalizedScopeId(),
                    message
            );
            log.debug("knowledge_graph_run_failed_stacktrace runId={}", runId, ex);
        }
    }

    private List<IndexedDocument> documentsForScope(KnowledgeGraphScope scope) {
        return switch (scope.scopeType()) {
            case ALL -> documentRepository.findAllParsedDocumentsForIndexing();
            case KNOWLEDGE_FOLDER -> documentRepository.findParsedDocumentsForIndexingByKnowledgeFolderId(scope.scopeId());
            case DOCUMENT -> documentRepository.findParsedDocumentForIndexing(scope.scopeId())
                    .map(List::of)
                    .orElseGet(List::of);
        };
    }

    private KnowledgeGraphScope resolveScope(String scopeType, String scopeId) {
        KnowledgeGraphScopeType type = parseScopeType(scopeType);
        if (type == KnowledgeGraphScopeType.ALL) {
            return new KnowledgeGraphScope(type, null, "全库");
        }
        String normalizedScopeId = scopeId == null ? "" : scopeId.strip();
        if (normalizedScopeId.isBlank()) {
            throw new KnowledgeGraphException(type.name() + " scope requires scopeId");
        }
        if (type == KnowledgeGraphScopeType.KNOWLEDGE_FOLDER) {
            KnowledgeFolder folder = folderRepository.findById(normalizedScopeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Knowledge folder not found: " + normalizedScopeId));
            if (!folder.enabled()) {
                throw new KnowledgeGraphException("Knowledge folder is disabled: " + folder.displayName());
            }
            return new KnowledgeGraphScope(type, normalizedScopeId, folder.displayName());
        }
        KnowledgeDocument document = documentRepository.findById(normalizedScopeId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found: " + normalizedScopeId));
        return new KnowledgeGraphScope(type, normalizedScopeId, document.fileName());
    }

    private static KnowledgeGraphScopeType parseScopeType(String scopeType) {
        if (scopeType == null || scopeType.isBlank()) {
            throw new KnowledgeGraphException("scopeType is required");
        }
        try {
            return KnowledgeGraphScopeType.valueOf(scopeType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeGraphException("Unsupported knowledge graph scopeType: " + scopeType);
        }
    }

    private static KnowledgeGraphViewType parseViewType(String viewType) {
        if (viewType == null || viewType.isBlank()) {
            throw new KnowledgeGraphException("viewType is required");
        }
        try {
            return KnowledgeGraphViewType.valueOf(viewType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeGraphException("Unsupported knowledge graph viewType: " + viewType);
        }
    }

    private static Long maxUpdatedAt(KnowledgeGraphView left, KnowledgeGraphView right) {
        Long leftTime = left == null ? null : left.updatedAt();
        Long rightTime = right == null ? null : right.updatedAt();
        if (leftTime == null) {
            return rightTime;
        }
        if (rightTime == null) {
            return leftTime;
        }
        return Math.max(leftTime, rightTime);
    }
}
