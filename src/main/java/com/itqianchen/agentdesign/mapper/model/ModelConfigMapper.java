package com.itqianchen.agentdesign.mapper.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ModelConfigMapper {

    List<ModelConfig> findAll(@Param("role") String role);

    List<ModelConfig> findById(@Param("id") String id);

    List<ModelConfig> findActive(@Param("role") String role);

    void save(ModelConfig config);

    void deactivateRole(@Param("role") String role, @Param("updatedAt") long updatedAt);

    void activate(@Param("id") String id, @Param("updatedAt") long updatedAt);

    void delete(@Param("id") String id);

    long countByRole(@Param("role") String role);

    List<ModelConfig> findLegacyActive(@Param("id") String id);

    void saveActive(ModelConfig config);
}
