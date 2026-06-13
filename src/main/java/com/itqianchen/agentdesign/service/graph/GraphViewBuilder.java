package com.itqianchen.agentdesign.service.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphViewType;
import com.itqianchen.agentdesign.domain.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphEvidenceDetailRow;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 从图谱事实生成前端视图 payload。
 * <p>视图是派生缓存，可从 nodes/edges/evidence 全量重建。</p>
 */
@Service
public class GraphViewBuilder {

    private static final int MINDMAP_ENTITY_LIMIT_PER_HEADING = 12;
    private static final int GRAPH_NODE_LIMIT = 100;

    private final KnowledgeGraphRepository repository;
    private final GraphCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;

    /**
     * 注入视图构建依赖。
     *
     * @param repository 图谱仓储
     * @param canonicalizer 图谱规范化工具
     * @param objectMapper JSON 编码器
     */
    public GraphViewBuilder(
            KnowledgeGraphRepository repository,
            GraphCanonicalizer canonicalizer,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建指定范围的所有前端视图。
     *
     * @param scope 图谱范围
     * @param runId 运行 ID
     * @param documents 本次参与构建的文档
     */
    public void buildViews(KnowledgeGraphScope scope, String runId, List<IndexedDocument> documents) {
        long now = System.currentTimeMillis();
        insertView(scope, runId, KnowledgeGraphViewType.MINDMAP, mindmapPayload(scope, documents), now);
        insertView(scope, runId, KnowledgeGraphViewType.GRAPH, graphPayload(scope), now);
    }

    /**
     * 写入单个视图快照。
     *
     * @param scope 图谱范围
     * @param runId 运行 ID
     * @param viewType 视图类型
     * @param payload 视图 payload
     * @param now 当前时间戳
     */
    private void insertView(
            KnowledgeGraphScope scope,
            String runId,
            KnowledgeGraphViewType viewType,
            Map<String, Object> payload,
            long now
    ) {
        try {
            String viewSeed = scope.scopeType().name() + "|" + scope.normalizedScopeId() + "|" + viewType.name();
            repository.insertView(new KnowledgeGraphView(
                    canonicalizer.stableId(viewSeed),
                    scope.scopeType(),
                    scope.normalizedScopeId(),
                    viewType,
                    objectMapper.writeValueAsString(payload),
                    runId,
                    now,
                    now
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize graph view payload", ex);
        }
    }

    /**
     * 构建思维导图 payload。
     *
     * @param scope 图谱范围
     * @param documents 文档快照
     * @return mindmap payload
     */
    private Map<String, Object> mindmapPayload(KnowledgeGraphScope scope, List<IndexedDocument> documents) {
        List<KnowledgeGraphEvidenceDetailRow> evidenceRows = repository.findNodeEvidenceByScope(scope);
        Map<String, List<KnowledgeGraphEvidenceDetailRow>> evidenceByChunk = new HashMap<>();
        for (KnowledgeGraphEvidenceDetailRow row : evidenceRows) {
            evidenceByChunk.computeIfAbsent(row.chunkId(), ignored -> new ArrayList<>()).add(row);
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(markdownLine(scope.displayName())).append('\n');
        documents.stream()
                .sorted(Comparator.comparing(IndexedDocument::fileName, String.CASE_INSENSITIVE_ORDER))
                .forEach(document -> appendDocumentMindmap(markdown, document, evidenceByChunk));

        if (documents.isEmpty()) {
            markdown.append("\n## 暂无可用文档\n");
        }
        List<Map<String, Object>> structuredDocuments = documents.stream()
                .sorted(Comparator.comparing(IndexedDocument::fileName, String.CASE_INSENSITIVE_ORDER))
                .map(document -> documentMindmapPayload(document, evidenceByChunk))
                .toList();
        return Map.of(
                "viewType", KnowledgeGraphViewType.MINDMAP.name(),
                "markdown", markdown.toString(),
                "root", Map.of(
                        "id", "scope",
                        "label", scope.displayName(),
                        "type", "SCOPE"
                ),
                "documents", structuredDocuments
        );
    }

    /**
     * 构建结构化文档导图节点。
     *
     * <p>Markdown 继续作为兼容字段保留；第 26 阶段前端优先消费该结构，避免再次解析模型文本。</p>
     *
     * @param document 文档快照
     * @param evidenceByChunk 按 chunk 分组的节点证据
     * @return 文档结构化 payload
     */
    private Map<String, Object> documentMindmapPayload(
            IndexedDocument document,
            Map<String, List<KnowledgeGraphEvidenceDetailRow>> evidenceByChunk
    ) {
        Map<String, HeadingBucket> headings = documentHeadings(document, evidenceByChunk);
        List<Map<String, Object>> headingPayload = headings.values().stream()
                .map(bucket -> Map.<String, Object>of(
                        "id", document.id() + "::heading::" + canonicalizer.canonicalName(bucket.heading),
                        "label", bucket.heading,
                        "entities", bucket.entities.entrySet().stream()
                                .sorted(Comparator.comparingInt((Map.Entry<String, EntityMention> entry) -> entry.getValue().count()).reversed()
                                        .thenComparing(entry -> entry.getValue().name(), String.CASE_INSENSITIVE_ORDER))
                                .limit(MINDMAP_ENTITY_LIMIT_PER_HEADING)
                                .map(entry -> Map.<String, Object>of(
                                        "id", entry.getKey(),
                                        "label", entry.getValue().name(),
                                        "type", entry.getValue().type(),
                                        "count", entry.getValue().count()
                                ))
                                .toList()
                ))
                .toList();
        return Map.of(
                "id", document.id(),
                "label", document.fileName(),
                "fileName", document.fileName(),
                "headings", headingPayload
        );
    }

    /**
     * 向 Markdown 思维导图追加单个文档。
     *
     * @param markdown Markdown 构建器
     * @param document 文档快照
     * @param evidenceByChunk 按 chunk 分组的节点证据
     */
    private void appendDocumentMindmap(
            StringBuilder markdown,
            IndexedDocument document,
            Map<String, List<KnowledgeGraphEvidenceDetailRow>> evidenceByChunk
    ) {
        markdown.append("\n## ").append(markdownLine(document.fileName())).append('\n');
        Map<String, HeadingBucket> headings = documentHeadings(document, evidenceByChunk);

        if (headings.isEmpty()) {
            markdown.append("\n### 暂无片段\n");
            return;
        }
        for (HeadingBucket bucket : headings.values()) {
            markdown.append("\n### ").append(markdownLine(bucket.heading)).append('\n');
            bucket.entities.values().stream()
                    .sorted(Comparator.comparingInt(EntityMention::count).reversed()
                            .thenComparing(EntityMention::name, String.CASE_INSENSITIVE_ORDER))
                    .limit(MINDMAP_ENTITY_LIMIT_PER_HEADING)
                    .forEach(entity -> markdown.append("#### ")
                            .append(markdownLine(entity.name()))
                            .append(" [")
                            .append(markdownLine(entity.type()))
                            .append("] x")
                            .append(entity.count())
                            .append('\n'));
        }
    }

    /**
     * 按文档内出现顺序聚合 heading 与实体提及。
     *
     * @param document 文档快照
     * @param evidenceByChunk 按 chunk 分组的节点证据
     * @return heading 到实体提及的有序映射
     */
    private Map<String, HeadingBucket> documentHeadings(
            IndexedDocument document,
            Map<String, List<KnowledgeGraphEvidenceDetailRow>> evidenceByChunk
    ) {
        Map<String, HeadingBucket> headings = new LinkedHashMap<>();
        for (IndexedChunk chunk : document.chunks()) {
            String heading = chunk.heading() == null || chunk.heading().isBlank()
                    ? "未命名片段"
                    : chunk.heading().strip();
            HeadingBucket bucket = headings.computeIfAbsent(heading, HeadingBucket::new);
            for (KnowledgeGraphEvidenceDetailRow row : evidenceByChunk.getOrDefault(chunk.id(), List.of())) {
                String nodeKey = row.nodeId();
                if (nodeKey == null || row.nodeDisplayName() == null || row.nodeDisplayName().isBlank()) {
                    continue;
                }
                bucket.entities.computeIfAbsent(
                        nodeKey,
                        ignored -> new EntityMention(row.nodeDisplayName(), row.nodeType())
                ).count++;
            }
        }
        return headings;
    }

    /**
     * 构建节点边图 payload。
     *
     * @param scope 图谱范围
     * @return graph payload
     */
    private Map<String, Object> graphPayload(KnowledgeGraphScope scope) {
        List<KnowledgeGraphNode> allNodes = repository.findNodesByScope(scope);
        List<KnowledgeGraphEdge> allEdges = repository.findEdgesByScope(scope);
        Map<String, Integer> degreeByNodeId = degreeByNodeId(allEdges);
        List<KnowledgeGraphNode> selectedNodes = allNodes.stream()
                .sorted(Comparator.comparingInt(KnowledgeGraphNode::mentionCount).reversed()
                        .thenComparing(node -> degreeByNodeId.getOrDefault(node.id(), 0), Comparator.reverseOrder())
                        .thenComparing(KnowledgeGraphNode::displayName, String.CASE_INSENSITIVE_ORDER))
                .limit(GRAPH_NODE_LIMIT)
                .toList();
        Set<String> selectedNodeIds = new HashSet<>(selectedNodes.stream().map(KnowledgeGraphNode::id).toList());
        Map<String, KnowledgeGraphNode> selectedNodeById = selectedNodes.stream()
                .collect(Collectors.toMap(KnowledgeGraphNode::id, Function.identity()));
        List<Map<String, Object>> nodes = selectedNodes.stream()
                .map(node -> Map.<String, Object>of(
                        "id", node.id(),
                        "label", node.displayName(),
                        "type", node.nodeType(),
                        "degree", degreeByNodeId.getOrDefault(node.id(), 0),
                        "mentionCount", node.mentionCount(),
                        "confidence", node.confidence()
                ))
                .toList();
        List<Map<String, Object>> edges = allEdges.stream()
                .filter(edge -> selectedNodeIds.contains(edge.sourceNodeId()) && selectedNodeIds.contains(edge.targetNodeId()))
                .map(edge -> Map.<String, Object>of(
                        "id", edge.id(),
                        "source", edge.sourceNodeId(),
                        "target", edge.targetNodeId(),
                        "sourceLabel", selectedNodeById.get(edge.sourceNodeId()).displayName(),
                        "targetLabel", selectedNodeById.get(edge.targetNodeId()).displayName(),
                        "label", edge.relationType(),
                        "weight", edge.mentionCount(),
                        "confidence", edge.confidence()
                ))
                .toList();

        return Map.of(
                "viewType", KnowledgeGraphViewType.GRAPH.name(),
                "nodeLimit", GRAPH_NODE_LIMIT,
                "totalNodeCount", allNodes.size(),
                "totalEdgeCount", allEdges.size(),
                "hiddenNodeCount", Math.max(0, allNodes.size() - selectedNodes.size()),
                "nodeTypeCounts", countBy(allNodes, KnowledgeGraphNode::nodeType),
                "relationTypeCounts", countBy(allEdges, KnowledgeGraphEdge::relationType),
                "nodes", nodes,
                "edges", edges
        );
    }

    /**
     * 生成前端图例和筛选所需的分类计数。
     *
     * @param values 原始集合
     * @param classifier 分类函数
     * @param <T> 集合元素类型
     * @return 分类值到数量的映射，按首次出现顺序稳定输出
     */
    private static <T> Map<String, Integer> countBy(Collection<T> values, Function<T, String> classifier) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T value : values) {
            String key = classifier.apply(value);
            if (key == null || key.isBlank()) {
                key = "UNKNOWN";
            }
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * 统计每个节点的度数。
     *
     * @param edges 边集合
     * @return 节点 ID 到度数的映射
     */
    private static Map<String, Integer> degreeByNodeId(Collection<KnowledgeGraphEdge> edges) {
        Map<String, Integer> degree = new HashMap<>();
        for (KnowledgeGraphEdge edge : edges) {
            degree.merge(edge.sourceNodeId(), 1, Integer::sum);
            degree.merge(edge.targetNodeId(), 1, Integer::sum);
        }
        return degree;
    }

    /**
     * 清洗 Markdown 单行文本。
     *
     * @param value 原始文本
     * @return 单行 Markdown 文本
     */
    private static String markdownLine(String value) {
        if (value == null || value.isBlank()) {
            return "未命名";
        }
        return value.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static class HeadingBucket {
        private final String heading;
        private final Map<String, EntityMention> entities = new LinkedHashMap<>();

        /**
         * 创建 heading 分桶。
         *
         * @param heading heading 文本
         */
        private HeadingBucket(String heading) {
            this.heading = heading;
        }
    }

    private static class EntityMention {
        private final String name;
        private final String type;
        private int count;

        /**
         * 创建实体提及计数。
         *
         * @param name 实体展示名
         * @param type 实体类型
         */
        private EntityMention(String name, String type) {
            this.name = name;
            this.type = type == null || type.isBlank() ? "ENTITY" : type;
        }

        /**
         * 返回实体名称。
         *
         * @return 实体名称
         */
        private String name() {
            return name;
        }

        /**
         * 返回实体类型。
         *
         * @return 实体类型
         */
        private String type() {
            return type;
        }

        /**
         * 返回实体提及次数。
         *
         * @return 提及次数
         */
        private int count() {
            return count;
        }
    }
}
