package com.itqianchen.agentdesign.repository.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.mapper.model.ModelConfigMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ModelConfigRepository {

    private final ModelConfigMapper modelConfigMapper;

    public ModelConfigRepository(ModelConfigMapper modelConfigMapper) {
        this.modelConfigMapper = modelConfigMapper;
    }

    public List<ModelConfig> findAll(ModelConfigRole role) {
        return modelConfigMapper.findAll(role.name());
    }

    public Optional<ModelConfig> findById(String id) {
        return modelConfigMapper.findById(id).stream().findFirst();
    }

    public Optional<ModelConfig> findActive(ModelConfigRole role) {
        return modelConfigMapper.findActive(role.name()).stream().findFirst();
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
        modelConfigMapper.save(config);
        return findById(config.id()).orElseThrow(() ->
                new IllegalStateException("Model config was not found after save"));
    }

    public ModelConfig activate(String id, long updatedAt) {
        ModelConfig target = findById(id).orElseThrow(() ->
                new ModelConfigurationException("Model config not found: " + id));
        // SQLite 没有 partial unique index 的跨版本保证时，应用层事务先清后设。
        // 这个约束是模型选择的核心：每个角色只能有一个 active 配置。
        modelConfigMapper.deactivateRole(target.role().name(), updatedAt);
        modelConfigMapper.activate(id, updatedAt);
        return findById(id).orElseThrow(() ->
                new IllegalStateException("Model config was not found after activate"));
    }

    public void delete(String id) {
        modelConfigMapper.delete(id);
    }

    public long countByRole(ModelConfigRole role) {
        return modelConfigMapper.countByRole(role.name());
    }

    @Deprecated
    public Optional<ModelConfig> findLegacyActive() {
        return modelConfigMapper.findLegacyActive(ModelConfigDefaults.ACTIVE_CONFIG_ID).stream().findFirst();
    }

    @Deprecated
    public ModelConfig saveActive(ModelConfig config) {
        modelConfigMapper.saveActive(new ModelConfig(
                config.id(),
                config.role(),
                config.provider(),
                config.displayName(),
                config.baseUrl(),
                config.apiKey(),
                config.modelName(),
                config.resolvedEmbeddingDimensions(),
                config.resolvedTemperature(),
                config.resolvedDefaultTopK(),
                config.active(),
                config.createdAt(),
                config.updatedAt()
        ));
        return findLegacyActive().orElseThrow(() ->
                new IllegalStateException("Model config was not found after save"));
    }
}
