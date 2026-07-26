package com.itqianchen.agentdesign.service.graph;


import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphRunStatus;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphViewType;
import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphEdge;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphEvidence;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphNode;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphRun;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphRunStatus;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphScope;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphScopeType;
import com.itqianchen.agentdesign.domain.entity.graph.KnowledgeGraphView;
import com.itqianchen.agentdesign.domain.enums.graph.KnowledgeGraphViewType;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphMapper;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphSummaryRow;
import com.itqianchen.agentdesign.support.DatabaseMigrationTestSupport;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteDataSource;

class KnowledgeGraphMapperTests {

    @Test
    void findGeneratedGraphSummariesDeduplicatesViewsByScope() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            KnowledgeGraphMapper graphMapper = sqlSession.getMapper(KnowledgeGraphMapper.class);
            long now = 1780000000000L;

            graphMapper.insertView(view(
                    "view-mindmap",
                    "folder-1",
                    KnowledgeGraphViewType.MINDMAP,
                    now - 100
            ));
            graphMapper.insertView(view(
                    "view-graph",
                    "folder-1",
                    KnowledgeGraphViewType.GRAPH,
                    now
            ));

            List<KnowledgeGraphSummaryRow> rows = graphMapper.findGeneratedGraphSummaries();

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().scopeType()).isEqualTo("KNOWLEDGE_FOLDER");
            assertThat(rows.getFirst().scopeId()).isEqualTo("folder-1");
            assertThat(rows.getFirst().mindmapReady()).isTrue();
            assertThat(rows.getFirst().graphReady()).isTrue();
            assertThat(rows.getFirst().generatedAt()).isEqualTo(now);
        }
    }

    @Test
    void deleteGeneratedGraphRemovesScopeFactsViewsAndRuns() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            KnowledgeGraphMapper graphMapper = sqlSession.getMapper(KnowledgeGraphMapper.class);
            KnowledgeGraphRepository repository = new KnowledgeGraphRepository(graphMapper);
            long now = 1780000000000L;
            KnowledgeGraphScope folderScope = new KnowledgeGraphScope(KnowledgeGraphScopeType.KNOWLEDGE_FOLDER, "folder-1", "项目资料");

            graphMapper.insertRun(run("run-1", "folder-1", now));
            graphMapper.insertNode(node("node-a", "folder-1", "Redis", now));
            graphMapper.insertNode(node("node-b", "folder-1", "Sentinel", now));
            graphMapper.insertEdge(edge("edge-1", "folder-1", now));
            graphMapper.insertEvidence(new KnowledgeGraphEvidence(
                    "evidence-1",
                    "run-1",
                    "node-a",
                    "edge-1",
                    "doc-1",
                    "chunk-1",
                    "Redis 使用 Sentinel",
                    0.9,
                    now
            ));
            graphMapper.insertView(view("view-graph", "folder-1", KnowledgeGraphViewType.GRAPH, now));

            repository.deleteGeneratedGraph(folderScope);

            assertThat(graphMapper.countNodesByScope("KNOWLEDGE_FOLDER", "folder-1")).isZero();
            assertThat(graphMapper.countEdgesByScope("KNOWLEDGE_FOLDER", "folder-1")).isZero();
            assertThat(graphMapper.findView("KNOWLEDGE_FOLDER", "folder-1", "GRAPH")).isEmpty();
            assertThat(graphMapper.findRunById("run-1")).isEmpty();
            assertThat(countRows(sqlSession.getConnection(), "knowledge_graph_evidence")).isZero();
        }
    }

    private static KnowledgeGraphView view(
            String id,
            String scopeId,
            KnowledgeGraphViewType viewType,
            long updatedAt
    ) {
        return new KnowledgeGraphView(
                id,
                KnowledgeGraphScopeType.KNOWLEDGE_FOLDER,
                scopeId,
                viewType,
                "{\"viewType\":\"" + viewType.name() + "\"}",
                "run-1",
                updatedAt - 1000,
                updatedAt
        );
    }

    private static KnowledgeGraphRun run(String id, String scopeId, long now) {
        return new KnowledgeGraphRun(
                id,
                KnowledgeGraphScopeType.KNOWLEDGE_FOLDER,
                scopeId,
                KnowledgeGraphRunStatus.COMPLETED,
                "chat-config-1",
                "kg-extract-v2",
                1,
                1,
                0,
                2,
                1,
                0,
                null,
                now - 1000,
                now,
                now - 1000,
                now
        );
    }

    private static KnowledgeGraphNode node(String id, String scopeId, String displayName, long now) {
        return new KnowledgeGraphNode(
                id,
                KnowledgeGraphScopeType.KNOWLEDGE_FOLDER,
                scopeId,
                displayName.toLowerCase(),
                displayName,
                "TECHNOLOGY",
                displayName,
                0.9,
                1,
                now,
                now
        );
    }

    private static KnowledgeGraphEdge edge(String id, String scopeId, long now) {
        return new KnowledgeGraphEdge(
                id,
                KnowledgeGraphScopeType.KNOWLEDGE_FOLDER,
                scopeId,
                "node-a",
                "node-b",
                "FUNCTIONAL",
                "使用",
                "Redis 使用 Sentinel",
                0.9,
                1,
                now,
                now
        );
    }

    private static long countRows(Connection connection, String tableName) {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to count rows for " + tableName, ex);
        }
    }

    private static SqlSession sqliteSqlSession() {
        try {
            SQLiteDataSource dataSource = DatabaseMigrationTestSupport.createMigratedDataSource();
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:/mappers/*.xml"));
            SqlSessionFactory factory = factoryBean.getObject();
            if (factory == null) {
                throw new IllegalStateException("Failed to create test MyBatis SqlSessionFactory");
            }
            return factory.openSession(true);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create in-memory SQLite MyBatis session", ex);
        }
    }
}
