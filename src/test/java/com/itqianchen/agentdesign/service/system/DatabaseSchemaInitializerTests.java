package com.itqianchen.agentdesign.service.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteDataSource;

class DatabaseSchemaInitializerTests {

    @Test
    void initializeCreatesCurrentSchemaIndexesAndDefaultModelConfigs() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            DatabaseSchemaMapper mapper = sqlSession.getMapper(DatabaseSchemaMapper.class);

            new DatabaseSchemaInitializer(mapper).initialize();

            assertThat(tableNames(sqlSession)).contains(
                    "knowledge_folders",
                    "documents",
                    "chunks",
                    "model_configs",
                    "chat_sessions",
                    "chat_messages",
                    "app_settings",
                    "knowledge_folder_runs",
                    "knowledge_graph_runs",
                    "knowledge_graph_chunk_extractions",
                    "knowledge_graph_nodes",
                    "knowledge_graph_edges",
                    "knowledge_graph_evidence",
                    "knowledge_graph_views"
            ).doesNotContain(
                    "model_config",
                    "knowledge_folder_runs_migration"
            );
            assertThat(columnNames(sqlSession, "documents")).contains("knowledge_folder_id");
            assertThat(columnNames(sqlSession, "model_configs")).contains("context_window_tokens");
            assertThat(columnNames(sqlSession, "chat_messages")).contains("agent_type", "references_json");
            assertThat(columnNames(sqlSession, "knowledge_graph_edges")).contains("display_label");
            assertThat(columnNames(sqlSession, "knowledge_folder_runs")).contains(
                    "phase",
                    "progress_current",
                    "progress_total",
                    "current_item",
                    "queued_at",
                    "updated_at"
            );
            assertThat(isNotNullColumn(sqlSession, "knowledge_folder_runs", "started_at")).isFalse();
            assertThat(isNotNullColumn(sqlSession, "knowledge_folder_runs", "completed_at")).isFalse();
            assertThat(isNotNullColumn(sqlSession, "knowledge_folder_runs", "duration_ms")).isFalse();
            assertThat(indexColumnNames(sqlSession, "idx_kg_edges_scope_triple")).containsExactly(
                    "scope_type",
                    "scope_id",
                    "source_node_id",
                    "target_node_id",
                    "relation_type",
                    "display_label"
            );
            assertThat(indexColumnNames(sqlSession, "idx_kg_edges_scope_triple_migration")).isEmpty();
            assertThat(queryInt(sqlSession, "SELECT COUNT(*) FROM model_configs")).isEqualTo(3);
            assertThat(queryInt(sqlSession, """
                    SELECT COUNT(*)
                    FROM model_configs
                    WHERE id = 'active-chat'
                      AND role = 'CHAT'
                      AND provider = 'DASHSCOPE'
                      AND model_name = 'qwen-plus'
                      AND context_window_tokens = 128000
                      AND is_active = 1
                    """)).isEqualTo(1);
            assertThat(queryInt(sqlSession, """
                    SELECT COUNT(*)
                    FROM model_configs
                    WHERE id = 'active-embedding'
                      AND role = 'EMBEDDING'
                      AND provider = 'DASHSCOPE'
                      AND model_name = 'text-embedding-v4'
                      AND embedding_dimensions = 1024
                      AND context_window_tokens IS NULL
                      AND is_active = 1
                    """)).isEqualTo(1);
            assertThat(queryInt(sqlSession, """
                    SELECT COUNT(*)
                    FROM model_configs
                    WHERE id = 'active-vision'
                      AND role = 'VISION'
                      AND provider = 'DASHSCOPE'
                      AND model_name = 'qwen3-vl-plus'
                      AND embedding_dimensions IS NULL
                      AND context_window_tokens IS NULL
                      AND is_active = 1
                    """)).isEqualTo(1);
        }
    }

    private static List<String> tableNames(SqlSession sqlSession) {
        List<String> names = new ArrayList<>();
        try (Statement statement = sqlSession.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT name
                     FROM sqlite_master
                     WHERE type = 'table'
                     ORDER BY name
                     """)) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect SQLite tables", ex);
        }
        return names;
    }

    private static List<String> columnNames(SqlSession sqlSession, String tableName) {
        List<String> names = new ArrayList<>();
        try (Statement statement = sqlSession.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect SQLite columns: " + tableName, ex);
        }
        return names;
    }

    private static boolean isNotNullColumn(SqlSession sqlSession, String tableName, String columnName) {
        try (Statement statement = sqlSession.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return resultSet.getInt("notnull") != 0;
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect SQLite column nullability: " + tableName, ex);
        }
        return false;
    }

    private static List<String> indexColumnNames(SqlSession sqlSession, String indexName) {
        List<String> names = new ArrayList<>();
        try (Statement statement = sqlSession.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_info(" + indexName + ")")) {
            while (resultSet.next()) {
                names.add(resultSet.getString("name"));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect SQLite index: " + indexName, ex);
        }
        return names;
    }

    private static int queryInt(SqlSession sqlSession, String sql) {
        try (Statement statement = sqlSession.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query SQLite scalar", ex);
        }
    }

    private static SqlSession sqliteSqlSession() {
        try {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite::memory:");
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
