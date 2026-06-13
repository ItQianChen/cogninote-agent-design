package com.itqianchen.agentdesign.service.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.document.FileType;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphViewType;
import com.itqianchen.agentdesign.domain.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphEvidenceDetailRow;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GraphViewBuilderTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeKnowledgeGraphRepository repository = new FakeKnowledgeGraphRepository();
    private final GraphViewBuilder builder = new GraphViewBuilder(
            repository,
            new GraphCanonicalizer(),
            objectMapper
    );

    @Test
    void mindmapPayloadKeepsMarkdownAndAddsStructuredDocuments() throws Exception {
        KnowledgeGraphScope scope = new KnowledgeGraphScope(
                KnowledgeGraphScopeType.DOCUMENT,
                "doc-1",
                "index.md"
        );
        repository.nodeEvidence = List.of(nodeEvidence("ev-1", "node-1", "chunk-1", "主从复制", "CONCEPT"));

        builder.buildViews(scope, "run-1", List.of(document("doc-1", chunk("chunk-1", "主从复制、读写分离与分库分表"))));

        JsonNode payload = repository.payload(KnowledgeGraphViewType.MINDMAP);
        assertThat(payload.path("markdown").asText()).contains("# index.md", "#### 主从复制 [CONCEPT] x1");
        assertThat(payload.path("root").path("label").asText()).isEqualTo("index.md");
        assertThat(payload.path("documents").size()).isEqualTo(1);
        assertThat(payload.path("documents").get(0).path("headings").size()).isEqualTo(1);
        assertThat(payload.path("documents").get(0).path("headings").get(0).path("entities").size()).isEqualTo(1);
        assertThat(payload.path("documents").get(0).path("headings").get(0).path("entities").get(0).path("id").asText())
                .isEqualTo("node-1");
    }

    @Test
    void graphPayloadAddsLegendSummariesAndEdgeLabels() throws Exception {
        KnowledgeGraphScope scope = new KnowledgeGraphScope(KnowledgeGraphScopeType.ALL, null, "全库");
        long now = 1780000000000L;
        repository.nodes = List.of(
                node("node-1", "CogniNote", "PRODUCT", 5, now),
                node("node-2", "Lucene", "TECHNOLOGY", 3, now)
        );
        repository.edges = List.of(edge("edge-1", "node-1", "node-2", "USES", 2, now));

        builder.buildViews(scope, "run-1", List.of());

        JsonNode payload = repository.payload(KnowledgeGraphViewType.GRAPH);
        assertThat(payload.path("hiddenNodeCount").asInt()).isZero();
        assertThat(payload.path("nodeTypeCounts").path("PRODUCT").asInt()).isEqualTo(1);
        assertThat(payload.path("nodeTypeCounts").path("TECHNOLOGY").asInt()).isEqualTo(1);
        assertThat(payload.path("relationTypeCounts").path("USES").asInt()).isEqualTo(1);
        JsonNode edge = payload.path("edges").get(0);
        assertThat(edge.path("sourceLabel").asText()).isEqualTo("CogniNote");
        assertThat(edge.path("targetLabel").asText()).isEqualTo("Lucene");
    }

    @Test
    void mindmapPayloadKeepsEmptyDocumentStateStructured() throws Exception {
        KnowledgeGraphScope scope = new KnowledgeGraphScope(KnowledgeGraphScopeType.ALL, null, "全库");

        builder.buildViews(scope, "run-1", List.of());

        JsonNode payload = repository.payload(KnowledgeGraphViewType.MINDMAP);
        assertThat(payload.path("markdown").asText()).contains("# 全库", "## 暂无可用文档");
        assertThat(payload.path("documents").isArray()).isTrue();
        assertThat(payload.path("documents").size()).isZero();
        assertThat(payload.path("root").path("type").asText()).isEqualTo("SCOPE");
    }

    @Test
    void graphPayloadHandlesEmptyGraph() throws Exception {
        KnowledgeGraphScope scope = new KnowledgeGraphScope(KnowledgeGraphScopeType.ALL, null, "全库");

        builder.buildViews(scope, "run-1", List.of());

        JsonNode payload = repository.payload(KnowledgeGraphViewType.GRAPH);
        assertThat(payload.path("totalNodeCount").asInt()).isZero();
        assertThat(payload.path("totalEdgeCount").asInt()).isZero();
        assertThat(payload.path("hiddenNodeCount").asInt()).isZero();
        assertThat(payload.path("nodes").size()).isZero();
        assertThat(payload.path("edges").size()).isZero();
    }

    @Test
    void graphPayloadReportsHiddenNodesWhenNodeLimitApplies() throws Exception {
        KnowledgeGraphScope scope = new KnowledgeGraphScope(KnowledgeGraphScopeType.ALL, null, "全库");
        long now = 1780000000000L;
        repository.nodes = java.util.stream.IntStream.rangeClosed(1, 101)
                .mapToObj(index -> node("node-" + index, "Entity " + index, "CONCEPT", index, now))
                .toList();

        builder.buildViews(scope, "run-1", List.of());

        JsonNode payload = repository.payload(KnowledgeGraphViewType.GRAPH);
        assertThat(payload.path("nodeLimit").asInt()).isEqualTo(100);
        assertThat(payload.path("totalNodeCount").asInt()).isEqualTo(101);
        assertThat(payload.path("hiddenNodeCount").asInt()).isEqualTo(1);
        assertThat(payload.path("nodes").size()).isEqualTo(100);
        assertThat(payload.path("nodes").get(0).path("label").asText()).isEqualTo("Entity 101");
    }

    private static IndexedDocument document(String id, IndexedChunk... chunks) {
        return new IndexedDocument(id, "D:/notes/index.md", "index.md", FileType.MARKDOWN, List.of(chunks));
    }

    private static IndexedChunk chunk(String id, String heading) {
        return new IndexedChunk(id, "doc-1", 0, "content", "hash", null, heading);
    }

    private static KnowledgeGraphNode node(String id, String name, String type, int mentions, long now) {
        return new KnowledgeGraphNode(
                id,
                KnowledgeGraphScopeType.ALL,
                null,
                name.toLowerCase(),
                name,
                type,
                "",
                0.9,
                mentions,
                now,
                now
        );
    }

    private static KnowledgeGraphEdge edge(String id, String source, String target, String type, int mentions, long now) {
        return new KnowledgeGraphEdge(
                id,
                KnowledgeGraphScopeType.ALL,
                null,
                source,
                target,
                type,
                "",
                0.8,
                mentions,
                now,
                now
        );
    }

    private static KnowledgeGraphEvidenceDetailRow nodeEvidence(
            String id,
            String nodeId,
            String chunkId,
            String nodeName,
            String nodeType
    ) {
        return new KnowledgeGraphEvidenceDetailRow(
                id,
                "run-1",
                nodeId,
                null,
                "doc-1",
                chunkId,
                nodeName,
                0.9,
                1780000000000L,
                "index.md",
                "D:/notes/index.md",
                "heading",
                null,
                0,
                nodeName,
                nodeType,
                null,
                null,
                null
        );
    }

    private class FakeKnowledgeGraphRepository extends KnowledgeGraphRepository {
        private List<KnowledgeGraphEvidenceDetailRow> nodeEvidence = List.of();
        private List<KnowledgeGraphNode> nodes = List.of();
        private List<KnowledgeGraphEdge> edges = List.of();
        private final Map<KnowledgeGraphViewType, KnowledgeGraphView> views = new EnumMap<>(KnowledgeGraphViewType.class);

        private FakeKnowledgeGraphRepository() {
            super(null);
        }

        @Override
        public List<KnowledgeGraphEvidenceDetailRow> findNodeEvidenceByScope(KnowledgeGraphScope scope) {
            return nodeEvidence;
        }

        @Override
        public List<KnowledgeGraphNode> findNodesByScope(KnowledgeGraphScope scope) {
            return nodes;
        }

        @Override
        public List<KnowledgeGraphEdge> findEdgesByScope(KnowledgeGraphScope scope) {
            return edges;
        }

        @Override
        public void insertView(KnowledgeGraphView view) {
            views.put(view.viewType(), view);
        }

        private JsonNode payload(KnowledgeGraphViewType viewType) throws Exception {
            KnowledgeGraphView view = views.get(viewType);
            assertThat(view).isNotNull();
            return objectMapper.readTree(view.payloadJson());
        }
    }
}
