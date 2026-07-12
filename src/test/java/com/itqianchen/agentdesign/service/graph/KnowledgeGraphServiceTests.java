package com.itqianchen.agentdesign.service.graph;


import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphRunStatus;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphViewType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.exception.graph.KnowledgeGraphException;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphRun;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphRunStatus;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphViewType;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.dto.graph.KnowledgeGraphSummaryResponse;
import com.itqianchen.agentdesign.domain.dto.graph.KnowledgeGraphViewResponse;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphSummaryRow;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeGraphServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeKnowledgeGraphRepository repository = new FakeKnowledgeGraphRepository();
    private final FakeKnowledgeFolderRepository folderRepository = new FakeKnowledgeFolderRepository();
    private final FakeDocumentRepository documentRepository = new FakeDocumentRepository();
    private final KnowledgeGraphService service = new KnowledgeGraphService(
            repository,
            folderRepository,
            documentRepository,
            null,
            null,
            null,
            null,
            Runnable::run,
            new GraphCanonicalizer(),
            objectMapper
    );

    @Test
    void listGeneratedGraphsReturnsReadableScopeSummaries() {
        long now = 1780000000000L;
        repository.summaries = List.of(
                new KnowledgeGraphSummaryRow("KNOWLEDGE_FOLDER", "folder-1", true, true, now),
                new KnowledgeGraphSummaryRow("DOCUMENT", "doc-1", true, false, now - 100),
                new KnowledgeGraphSummaryRow("ALL", null, false, true, now - 200)
        );
        repository.nodeCounts.put("KNOWLEDGE_FOLDER:folder-1", 8);
        repository.edgeCounts.put("KNOWLEDGE_FOLDER:folder-1", 5);
        repository.nodeCounts.put("DOCUMENT:doc-1", 3);
        repository.edgeCounts.put("DOCUMENT:doc-1", 2);
        repository.nodeCounts.put("ALL:", 12);
        repository.edgeCounts.put("ALL:", 9);
        repository.latestRuns.put("KNOWLEDGE_FOLDER:folder-1", run("run-folder", KnowledgeGraphScopeType.KNOWLEDGE_FOLDER, "folder-1", now));
        folderRepository.folders.put("folder-1", folder("folder-1", "项目资料", "D:/notes/project", now));
        documentRepository.documents.put("doc-1", document("doc-1", "README.md", "D:/notes/project/README.md", now));

        List<KnowledgeGraphSummaryResponse> responses = service.listGeneratedGraphs();

        KnowledgeGraphSummaryResponse folderSummary = responses.get(0);
        assertThat(folderSummary.scopeType()).isEqualTo("KNOWLEDGE_FOLDER");
        assertThat(folderSummary.scopeId()).isEqualTo("folder-1");
        assertThat(folderSummary.scopeName()).isEqualTo("项目资料");
        assertThat(folderSummary.scopeSubtitle()).isEqualTo("D:/notes/project");
        assertThat(folderSummary.nodeCount()).isEqualTo(8);
        assertThat(folderSummary.edgeCount()).isEqualTo(5);
        assertThat(folderSummary.mindmapReady()).isTrue();
        assertThat(folderSummary.graphReady()).isTrue();
        assertThat(folderSummary.latestRun().runId()).isEqualTo("run-folder");

        KnowledgeGraphSummaryResponse documentSummary = responses.get(1);
        assertThat(documentSummary.scopeType()).isEqualTo("DOCUMENT");
        assertThat(documentSummary.scopeName()).isEqualTo("README.md");
        assertThat(documentSummary.scopeSubtitle()).isEqualTo("D:/notes/project/README.md");
        assertThat(documentSummary.nodeCount()).isEqualTo(3);
        assertThat(documentSummary.edgeCount()).isEqualTo(2);
        assertThat(documentSummary.mindmapReady()).isTrue();
        assertThat(documentSummary.graphReady()).isFalse();

        KnowledgeGraphSummaryResponse allSummary = responses.get(2);
        assertThat(allSummary.scopeType()).isEqualTo("ALL");
        assertThat(allSummary.scopeId()).isNull();
        assertThat(allSummary.scopeName()).isEqualTo("全库");
        assertThat(allSummary.scopeSubtitle()).isEqualTo("全部范围");
        assertThat(allSummary.nodeCount()).isEqualTo(12);
        assertThat(allSummary.edgeCount()).isEqualTo(9);
    }

    @Test
    void listGeneratedGraphsFallsBackWhenScopeTargetWasDeleted() {
        long now = 1780000000000L;
        repository.summaries = List.of(
                new KnowledgeGraphSummaryRow("KNOWLEDGE_FOLDER", "missing-folder", true, false, now),
                new KnowledgeGraphSummaryRow("DOCUMENT", "missing-doc", false, true, now - 100)
        );

        List<KnowledgeGraphSummaryResponse> responses = service.listGeneratedGraphs();

        assertThat(responses.get(0).scopeName()).isEqualTo("已删除目录");
        assertThat(responses.get(0).scopeSubtitle()).isEqualTo("missing-folder");
        assertThat(responses.get(1).scopeName()).isEqualTo("已删除文档");
        assertThat(responses.get(1).scopeSubtitle()).isEqualTo("missing-doc");
    }

    @Test
    void viewEnrichesLegacyGraphPayloadWithDisplayLabelAndCoarseType() {
        long now = 1780000000000L;
        repository.view = new KnowledgeGraphView(
                "view-1",
                KnowledgeGraphScopeType.ALL,
                null,
                KnowledgeGraphViewType.GRAPH,
                """
                        {
                          "viewType": "GRAPH",
                          "nodes": [],
                          "edges": [
                            {
                              "id":"edge-1",
                              "source":"node-a",
                              "target":"node-b",
                              "sourceLabel":"Redis",
                              "targetLabel":"Sentinel",
                              "label":"USES",
                              "description":"Redis uses Sentinel"
                            },
                            {
                              "id":"edge-2",
                              "source":"node-b",
                              "target":"node-c",
                              "sourceLabel":"Sentinel",
                              "targetLabel":"客户端",
                              "label":"Notifies About"
                            }
                          ]
                        }
                        """,
                "run-1",
                now,
                now
        );
        repository.edges = List.of(new KnowledgeGraphEdge(
                "edge-1",
                KnowledgeGraphScopeType.ALL,
                null,
                "node-a",
                "node-b",
                "FUNCTIONAL",
                "使用",
                "Redis uses Sentinel",
                0.88,
                2,
                now,
                now
        ));

        KnowledgeGraphViewResponse response = service.view("ALL", null, "GRAPH");

        JsonNode firstEdge = response.payload().path("edges").get(0);
        assertThat(firstEdge.path("label").asText()).isEqualTo("FUNCTIONAL");
        assertThat(firstEdge.path("displayLabel").asText()).isEqualTo("使用");
        assertThat(firstEdge.path("description").asText()).isEqualTo("Redis 使用 Sentinel。");
        JsonNode secondEdge = response.payload().path("edges").get(1);
        assertThat(secondEdge.path("label").asText()).isEqualTo("CAUSAL");
        assertThat(secondEdge.path("displayLabel").asText()).isEqualTo("相关");
        assertThat(secondEdge.path("description").asText()).isEqualTo("Sentinel 相关 客户端。");
    }

    @Test
    void deleteGeneratedGraphAcceptsDeletedScopeTarget() {
        service.deleteGeneratedGraph("DOCUMENT", "missing-doc");

        assertThat(repository.deletedScopes).containsExactly("DOCUMENT:missing-doc");
    }

    @Test
    void deleteGeneratedGraphRejectsActiveRun() {
        long now = 1780000000000L;
        repository.activeRuns.put(
                "KNOWLEDGE_FOLDER:folder-1",
                run("run-active", KnowledgeGraphScopeType.KNOWLEDGE_FOLDER, "folder-1", KnowledgeGraphRunStatus.RUNNING, now)
        );

        assertThatThrownBy(() -> service.deleteGeneratedGraph("KNOWLEDGE_FOLDER", "folder-1"))
                .isInstanceOf(KnowledgeGraphException.class)
                .hasMessageContaining("正在生成");
        assertThat(repository.deletedScopes).isEmpty();
    }

    private static final class FakeKnowledgeGraphRepository extends KnowledgeGraphRepository {
        private KnowledgeGraphView view;
        private List<KnowledgeGraphEdge> edges = List.of();
        private List<KnowledgeGraphSummaryRow> summaries = List.of();
        private final Map<String, Integer> nodeCounts = new HashMap<>();
        private final Map<String, Integer> edgeCounts = new HashMap<>();
        private final Map<String, KnowledgeGraphRun> latestRuns = new HashMap<>();
        private final Map<String, KnowledgeGraphRun> activeRuns = new HashMap<>();
        private final List<String> deletedScopes = new java.util.ArrayList<>();

        private FakeKnowledgeGraphRepository() {
            super(null);
        }

        @Override
        public List<KnowledgeGraphSummaryRow> findGeneratedGraphSummaries() {
            return summaries;
        }

        @Override
        public int countNodesByScope(KnowledgeGraphScope scope) {
            return nodeCounts.getOrDefault(scopeKey(scope), 0);
        }

        @Override
        public int countEdgesByScope(KnowledgeGraphScope scope) {
            return edgeCounts.getOrDefault(scopeKey(scope), 0);
        }

        @Override
        public Optional<KnowledgeGraphRun> findLatestRunForScope(KnowledgeGraphScope scope) {
            return Optional.ofNullable(latestRuns.get(scopeKey(scope)));
        }

        @Override
        public Optional<KnowledgeGraphRun> findActiveRun(KnowledgeGraphScope scope) {
            return Optional.ofNullable(activeRuns.get(scopeKey(scope)));
        }

        @Override
        public void deleteGeneratedGraph(KnowledgeGraphScope scope) {
            deletedScopes.add(scopeKey(scope));
        }

        @Override
        public Optional<KnowledgeGraphView> findView(KnowledgeGraphScope scope, String viewType) {
            return Optional.ofNullable(view);
        }

        @Override
        public List<KnowledgeGraphEdge> findEdgesByScope(KnowledgeGraphScope scope) {
            return edges;
        }
    }

    private static final class FakeKnowledgeFolderRepository extends KnowledgeFolderRepository {
        private final Map<String, KnowledgeFolder> folders = new HashMap<>();

        private FakeKnowledgeFolderRepository() {
            super(null);
        }

        @Override
        public Optional<KnowledgeFolder> findById(String id) {
            return Optional.ofNullable(folders.get(id));
        }
    }

    private static final class FakeDocumentRepository extends DocumentRepository {
        private final Map<String, KnowledgeDocument> documents = new HashMap<>();

        private FakeDocumentRepository() {
            super(null, null);
        }

        @Override
        public Optional<KnowledgeDocument> findById(String id) {
            return Optional.ofNullable(documents.get(id));
        }
    }

    private static String scopeKey(KnowledgeGraphScope scope) {
        return scope.scopeType().name() + ":" + (scope.normalizedScopeId() == null ? "" : scope.normalizedScopeId());
    }

    private static KnowledgeGraphRun run(
            String id,
            KnowledgeGraphScopeType scopeType,
            String scopeId,
            long now
    ) {
        return run(id, scopeType, scopeId, KnowledgeGraphRunStatus.COMPLETED, now);
    }

    private static KnowledgeGraphRun run(
            String id,
            KnowledgeGraphScopeType scopeType,
            String scopeId,
            KnowledgeGraphRunStatus status,
            long now
    ) {
        return new KnowledgeGraphRun(
                id,
                scopeType,
                scopeId,
                status,
                "chat-config-1",
                "kg-extract-v2",
                4,
                4,
                1,
                8,
                5,
                0,
                null,
                now - 1000,
                now,
                now - 1000,
                now
        );
    }

    private static KnowledgeFolder folder(String id, String name, String path, long now) {
        return new KnowledgeFolder(id, path, name, true, true, now, now, now, now);
    }

    private static KnowledgeDocument document(String id, String name, String path, long now) {
        return new KnowledgeDocument(
                id,
                "folder-1",
                path,
                name,
                FileType.MARKDOWN,
                1024,
                now,
                "hash-" + id,
                DocumentStatus.PARSED,
                now,
                now,
                now,
                3
        );
    }
}
