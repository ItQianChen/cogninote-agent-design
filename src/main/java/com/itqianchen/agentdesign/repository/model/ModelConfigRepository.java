package com.itqianchen.agentdesign.repository.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ModelConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ModelConfig> findAll(ModelConfigRole role) {
        return jdbcTemplate.query("""
                        SELECT id, role, provider, display_name, base_url, api_key, model_name,
                               embedding_dimensions, temperature, default_top_k, is_active, created_at, updated_at
                        FROM model_configs
                        WHERE role = ?
                        ORDER BY is_active DESC, updated_at DESC
                        """,
                (rs, rowNum) -> mapConfig(rs),
                role.name()
        );
    }

    public Optional<ModelConfig> findById(String id) {
        List<ModelConfig> configs = jdbcTemplate.query("""
                        SELECT id, role, provider, display_name, base_url, api_key, model_name,
                               embedding_dimensions, temperature, default_top_k, is_active, created_at, updated_at
                        FROM model_configs
                        WHERE id = ?
                        """,
                (rs, rowNum) -> mapConfig(rs),
                id
        );
        return configs.stream().findFirst();
    }

    public Optional<ModelConfig> findActive(ModelConfigRole role) {
        List<ModelConfig> configs = jdbcTemplate.query("""
                        SELECT id, role, provider, display_name, base_url, api_key, model_name,
                               embedding_dimensions, temperature, default_top_k, is_active, created_at, updated_at
                        FROM model_configs
                        WHERE role = ? AND is_active = 1
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> mapConfig(rs),
                role.name()
        );
        return configs.stream().findFirst();
    }

    public Optional<ModelConfig> findActiveChat() {
        return findActive(ModelConfigRole.CHAT);
    }

    public Optional<ModelConfig> findActiveEmbedding() {
        return findActive(ModelConfigRole.EMBEDDING);
    }

    @Deprecated
    public Optional<ModelConfig> findActive() {
        return findActiveChat();
    }

    public ModelConfig save(ModelConfig config) {
        jdbcTemplate.update("""
                        INSERT INTO model_configs (
                            id, role, provider, display_name, base_url, api_key, model_name,
                            embedding_dimensions, temperature, default_top_k, is_active, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            role = excluded.role,
                            provider = excluded.provider,
                            display_name = excluded.display_name,
                            base_url = excluded.base_url,
                            api_key = excluded.api_key,
                            model_name = excluded.model_name,
                            embedding_dimensions = excluded.embedding_dimensions,
                            temperature = excluded.temperature,
                            default_top_k = excluded.default_top_k,
                            is_active = excluded.is_active,
                            updated_at = excluded.updated_at
                        """,
                config.id(),
                config.role().name(),
                config.provider().name(),
                config.displayName(),
                config.baseUrl(),
                config.apiKey(),
                config.modelName(),
                config.embeddingDimensions(),
                config.temperature(),
                config.defaultTopK(),
                config.active() ? 1 : 0,
                config.createdAt(),
                config.updatedAt()
        );
        return findById(config.id()).orElseThrow(() ->
                new IllegalStateException("Model config was not found after save"));
    }

    public ModelConfig activate(String id, long updatedAt) {
        ModelConfig target = findById(id).orElseThrow(() ->
                new ModelConfigurationException("Model config not found: " + id));
        // SQLite 没有 partial unique index 的跨版本保证时，应用层事务先清后设。
        // 这个约束是模型选择的核心：每个角色只能有一个 active 配置。
        jdbcTemplate.update("UPDATE model_configs SET is_active = 0, updated_at = ? WHERE role = ?",
                updatedAt, target.role().name());
        jdbcTemplate.update("UPDATE model_configs SET is_active = 1, updated_at = ? WHERE id = ?",
                updatedAt, id);
        return findById(id).orElseThrow(() ->
                new IllegalStateException("Model config was not found after activate"));
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM model_configs WHERE id = ?", id);
    }

    public long countByRole(ModelConfigRole role) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_configs WHERE role = ?",
                Long.class,
                role.name()
        );
        return count == null ? 0 : count;
    }

    @Deprecated
    public Optional<ModelConfig> findLegacyActive() {
        List<ModelConfig> configs = jdbcTemplate.query("""
                        SELECT id, provider, display_name, base_url, api_key, chat_model, embedding_model,
                               embedding_dimensions, temperature, top_k, created_at, updated_at
                        FROM model_config
                        WHERE id = ?
                        """,
                (rs, rowNum) -> mapLegacyChatConfig(rs),
                ModelConfigDefaults.ACTIVE_CONFIG_ID
        );
        return configs.stream().findFirst();
    }

    @Deprecated
    public ModelConfig saveActive(ModelConfig config) {
        jdbcTemplate.update("""
                        INSERT INTO model_config (
                            id, provider, display_name, base_url, api_key, chat_model, embedding_model,
                            embedding_dimensions, temperature, top_k, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            provider = excluded.provider,
                            display_name = excluded.display_name,
                            base_url = excluded.base_url,
                            api_key = excluded.api_key,
                            chat_model = excluded.chat_model,
                            embedding_model = excluded.embedding_model,
                            embedding_dimensions = excluded.embedding_dimensions,
                            temperature = excluded.temperature,
                            top_k = excluded.top_k,
                            updated_at = excluded.updated_at
                        """,
                config.id(),
                config.provider().name(),
                config.displayName(),
                config.baseUrl(),
                config.apiKey(),
                config.modelName(),
                config.modelName(),
                config.resolvedEmbeddingDimensions(),
                config.resolvedTemperature(),
                config.resolvedDefaultTopK(),
                config.createdAt(),
                config.updatedAt()
        );
        return findLegacyActive().orElseThrow(() ->
                new IllegalStateException("Model config was not found after save"));
    }

    private static ModelConfig mapConfig(ResultSet rs) throws SQLException {
        return new ModelConfig(
                rs.getString("id"),
                ModelConfigRole.valueOf(rs.getString("role")),
                ModelProvider.valueOf(rs.getString("provider")),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("model_name"),
                nullableInt(rs, "embedding_dimensions"),
                nullableDouble(rs, "temperature"),
                nullableInt(rs, "default_top_k"),
                rs.getInt("is_active") == 1,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private static ModelConfig mapLegacyChatConfig(ResultSet rs) throws SQLException {
        return new ModelConfig(
                rs.getString("id"),
                ModelConfigRole.CHAT,
                ModelProvider.valueOf(rs.getString("provider")),
                rs.getString("display_name"),
                rs.getString("base_url"),
                rs.getString("api_key"),
                rs.getString("chat_model"),
                null,
                rs.getDouble("temperature"),
                rs.getInt("top_k"),
                true,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private static Integer nullableInt(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }
}


