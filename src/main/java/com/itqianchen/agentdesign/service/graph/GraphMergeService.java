package com.itqianchen.agentdesign.service.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphChunkExtraction;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEvidence;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphExtractionStatus;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphPromptProperties;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图谱派生层 merge 服务。
 * <p>merge 只使用本地缓存和 SQLite 短事务，不再调用模型。</p>
 */
@Service
public class GraphMergeService {

    private static final Logger log = LoggerFactory.getLogger(GraphMergeService.class);

    private final KnowledgeGraphRepository repository;
    private final GraphCanonicalizer canonicalizer;
    private final GraphViewBuilder viewBuilder;
    private final ObjectMapper objectMapper;
    private final KnowledgeGraphPromptProperties promptProperties;

    public GraphMergeService(
            KnowledgeGraphRepository repository,
            GraphCanonicalizer canonicalizer,
            GraphViewBuilder viewBuilder,
            ObjectMapper objectMapper,
            KnowledgeGraphPromptProperties promptProperties
    ) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.viewBuilder = viewBuilder;
        this.objectMapper = objectMapper;
        this.promptProperties = promptProperties;
    }

    @Transactional
    public GraphMergeResult merge(
            KnowledgeGraphScope scope,
            String runId,
            List<IndexedDocument> documents,
            String modelConfigId
    ) {
        Map<String, IndexedChunk> chunksById = chunksById(documents);
        Map<NodeKey, NodeAccumulator> nodes = new LinkedHashMap<>();
        Map<EdgeKey, EdgeAccumulator> edges = new LinkedHashMap<>();
        long now = System.currentTimeMillis();

        repository.deleteScopeDerivedGraph(scope);
        for (KnowledgeGraphChunkExtraction extraction : repository.findExtractionsByChunkIds(new ArrayList<>(chunksById.keySet()))) {
            IndexedChunk chunk = chunksById.get(extraction.chunkId());
            if (!isReusableExtraction(extraction, chunk, modelConfigId)) {
                continue;
            }
            mergeExtraction(scope, runId, chunk, extraction, nodes, edges, now);
        }

        List<NodeAccumulator> persistedNodes = nodes.values().stream()
                .filter(node -> node.mentionCount > 0)
                .toList();
        Set<String> persistedNodeIds = new HashSet<>(persistedNodes.stream().map(NodeAccumulator::id).toList());
        List<EdgeAccumulator> persistedEdges = edges.values().stream()
                .filter(edge -> edge.mentionCount > 0)
                .filter(edge -> persistedNodeIds.contains(edge.sourceNodeId) && persistedNodeIds.contains(edge.targetNodeId))
                .toList();

        for (NodeAccumulator node : persistedNodes) {
            repository.insertNode(node.toNode(scope, now));
        }
        for (EdgeAccumulator edge : persistedEdges) {
            repository.insertEdge(edge.toEdge(scope, now));
        }
        for (NodeAccumulator node : persistedNodes) {
            for (KnowledgeGraphEvidence evidence : node.evidence) {
                repository.insertEvidence(evidence);
            }
        }
        for (EdgeAccumulator edge : persistedEdges) {
            for (KnowledgeGraphEvidence evidence : edge.evidence) {
                repository.insertEvidence(evidence);
            }
        }

        viewBuilder.buildViews(scope, runId, documents);
        log.info("knowledge_graph_merged runId={} scopeType={} scopeId={} nodes={} edges={}",
                runId,
                scope.scopeType(),
                scope.normalizedScopeId(),
                persistedNodes.size(),
                persistedEdges.size()
        );
        return new GraphMergeResult(persistedNodes.size(), persistedEdges.size());
    }

    private void mergeExtraction(
            KnowledgeGraphScope scope,
            String runId,
            IndexedChunk chunk,
            KnowledgeGraphChunkExtraction extraction,
            Map<NodeKey, NodeAccumulator> nodes,
            Map<EdgeKey, EdgeAccumulator> edges,
            long now
    ) {
        GraphExtractionPayload payload = parse(extraction.extractionJson());
        Map<String, NodeKey> localNameToNodeKey = new HashMap<>();
        for (GraphExtractionPayload.Node extractedNode : nullToEmpty(payload.nodes())) {
            String canonicalName = canonicalizer.canonicalName(extractedNode.name());
            if (canonicalName.isBlank()) {
                continue;
            }
            String nodeType = canonicalizer.nodeType(extractedNode.type());
            NodeKey key = new NodeKey(canonicalName, nodeType);
            NodeAccumulator node = nodes.computeIfAbsent(
                    key,
                    ignored -> new NodeAccumulator(scope, key, extractedNode.name(), nodeType)
            );
            localNameToNodeKey.putIfAbsent(canonicalName, key);
            if (canonicalizer.quoteMatches(chunk.content(), extractedNode.quote())) {
                node.addEvidence(
                        runId,
                        chunk,
                        extractedNode.description(),
                        normalizeConfidence(extractedNode.confidence()),
                        extractedNode.quote(),
                        false,
                        now
                );
            }
        }

        for (GraphExtractionPayload.Edge extractedEdge : nullToEmpty(payload.edges())) {
            NodeKey sourceKey = localNameToNodeKey.get(canonicalizer.canonicalName(extractedEdge.source()));
            NodeKey targetKey = localNameToNodeKey.get(canonicalizer.canonicalName(extractedEdge.target()));
            if (sourceKey == null || targetKey == null || Objects.equals(sourceKey, targetKey)) {
                continue;
            }
            if (!canonicalizer.quoteMatches(chunk.content(), extractedEdge.quote())) {
                continue;
            }

            NodeAccumulator source = nodes.get(sourceKey);
            NodeAccumulator target = nodes.get(targetKey);
            if (source == null || target == null) {
                continue;
            }
            double confidence = normalizeConfidence(extractedEdge.confidence());
            source.addEvidence(runId, chunk, null, confidence, extractedEdge.quote(), true, now);
            target.addEvidence(runId, chunk, null, confidence, extractedEdge.quote(), true, now);

            String relationType = canonicalizer.relationType(extractedEdge.type());
            EdgeKey edgeKey = new EdgeKey(source.id(), target.id(), relationType);
            EdgeAccumulator edge = edges.computeIfAbsent(
                    edgeKey,
                    ignored -> new EdgeAccumulator(edgeKey, relationType)
            );
            edge.addEvidence(runId, chunk, extractedEdge.description(), confidence, extractedEdge.quote(), now);
        }
    }

    private GraphExtractionPayload parse(String json) {
        try {
            return objectMapper.readValue(json, GraphExtractionPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("cached graph extraction json is invalid", ex);
        }
    }

    private boolean isReusableExtraction(KnowledgeGraphChunkExtraction extraction, IndexedChunk chunk, String modelConfigId) {
        return chunk != null
                && extraction.status() == KnowledgeGraphExtractionStatus.EXTRACTED
                && Objects.equals(extraction.contentHash(), chunk.contentHash())
                && Objects.equals(extraction.promptVersion(), promptProperties.extraction().version())
                && Objects.equals(extraction.modelConfigId(), modelConfigId)
                && extraction.extractionJson() != null
                && !extraction.extractionJson().isBlank();
    }

    private static Map<String, IndexedChunk> chunksById(Collection<IndexedDocument> documents) {
        Map<String, IndexedChunk> chunks = new LinkedHashMap<>();
        for (IndexedDocument document : documents) {
            for (IndexedChunk chunk : document.chunks()) {
                chunks.put(chunk.id(), chunk);
            }
        }
        return chunks;
    }

    private static double normalizeConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return 0.0;
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record NodeKey(String canonicalName, String nodeType) {
    }

    private record EdgeKey(String sourceNodeId, String targetNodeId, String relationType) {
    }

    private class NodeAccumulator {
        private final NodeKey key;
        private final String id;
        private final String displayName;
        private final String nodeType;
        private final List<KnowledgeGraphEvidence> evidence = new ArrayList<>();
        private final Set<String> evidenceIds = new HashSet<>();
        private String description;
        private double confidenceTotal;
        private int mentionCount;

        private NodeAccumulator(KnowledgeGraphScope scope, NodeKey key, String displayName, String nodeType) {
            this.key = key;
            this.id = canonicalizer.stableId(scopeSeed(scope) + "|node|" + key.canonicalName() + "|" + key.nodeType());
            this.displayName = canonicalizer.displayText(displayName, 120);
            this.nodeType = nodeType;
        }

        private String id() {
            return id;
        }

        private void addEvidence(
                String runId,
                IndexedChunk chunk,
                String description,
                double confidence,
                String quote,
                boolean fromEdge,
                long now
        ) {
            String normalizedQuote = canonicalizer.displayText(quote, 260);
            if (normalizedQuote.isBlank()) {
                return;
            }
            String evidenceId = canonicalizer.stableId(runId + "|node|" + id + "|" + chunk.id() + "|" + normalizedQuote);
            if (!evidenceIds.add(evidenceId)) {
                return;
            }
            mentionCount++;
            confidenceTotal += confidence;
            if (!fromEdge && this.description == null && description != null && !description.isBlank()) {
                this.description = canonicalizer.displayText(description, 280);
            }
            evidence.add(new KnowledgeGraphEvidence(
                    evidenceId,
                    runId,
                    id,
                    null,
                    chunk.documentId(),
                    chunk.id(),
                    normalizedQuote,
                    confidence,
                    now
            ));
        }

        private KnowledgeGraphNode toNode(KnowledgeGraphScope scope, long now) {
            return new KnowledgeGraphNode(
                    id,
                    scope.scopeType(),
                    scope.normalizedScopeId(),
                    key.canonicalName(),
                    displayName,
                    nodeType,
                    description,
                    mentionCount == 0 ? 0 : confidenceTotal / mentionCount,
                    mentionCount,
                    now,
                    now
            );
        }
    }

    private class EdgeAccumulator {
        private final EdgeKey key;
        private final String id;
        private final String sourceNodeId;
        private final String targetNodeId;
        private final String relationType;
        private final List<KnowledgeGraphEvidence> evidence = new ArrayList<>();
        private final Set<String> evidenceIds = new HashSet<>();
        private String description;
        private double confidenceTotal;
        private int mentionCount;

        private EdgeAccumulator(EdgeKey key, String relationType) {
            this.key = key;
            this.id = canonicalizer.stableId("edge|" + key.sourceNodeId() + "|" + key.targetNodeId() + "|" + key.relationType());
            this.sourceNodeId = key.sourceNodeId();
            this.targetNodeId = key.targetNodeId();
            this.relationType = relationType;
        }

        private void addEvidence(
                String runId,
                IndexedChunk chunk,
                String description,
                double confidence,
                String quote,
                long now
        ) {
            String normalizedQuote = canonicalizer.displayText(quote, 260);
            if (normalizedQuote.isBlank()) {
                return;
            }
            String evidenceId = canonicalizer.stableId(runId + "|edge|" + id + "|" + chunk.id() + "|" + normalizedQuote);
            if (!evidenceIds.add(evidenceId)) {
                return;
            }
            mentionCount++;
            confidenceTotal += confidence;
            if (this.description == null && description != null && !description.isBlank()) {
                this.description = canonicalizer.displayText(description, 280);
            }
            evidence.add(new KnowledgeGraphEvidence(
                    evidenceId,
                    runId,
                    null,
                    id,
                    chunk.documentId(),
                    chunk.id(),
                    normalizedQuote,
                    confidence,
                    now
            ));
        }

        private KnowledgeGraphEdge toEdge(KnowledgeGraphScope scope, long now) {
            return new KnowledgeGraphEdge(
                    id,
                    scope.scopeType(),
                    scope.normalizedScopeId(),
                    sourceNodeId,
                    targetNodeId,
                    relationType,
                    description,
                    mentionCount == 0 ? 0 : confidenceTotal / mentionCount,
                    mentionCount,
                    now,
                    now
            );
        }
    }

    private static String scopeSeed(KnowledgeGraphScope scope) {
        return scope.scopeType().name() + "|" + (scope.normalizedScopeId() == null ? "" : scope.normalizedScopeId());
    }
}
