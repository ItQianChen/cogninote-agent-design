package com.itqianchen.agentdesign.controller.model;

import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.chat.LlmGateway;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.dto.model.ActiveModelConfigsResponse;
import com.itqianchen.agentdesign.dto.model.LegacyModelConfigResponse;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.dto.model.ModelConfigResponse;
import com.itqianchen.agentdesign.dto.model.ModelConfigTestResponse;
import com.itqianchen.agentdesign.dto.model.ModelOptionsResponse;
import com.itqianchen.agentdesign.service.model.ModelCatalogService;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelCatalogService modelCatalogService;
    private final LlmGateway llmGateway;

    public ModelConfigController(
            ModelConfigService modelConfigService,
            ModelCatalogService modelCatalogService,
            LlmGateway llmGateway
    ) {
        this.modelConfigService = modelConfigService;
        this.modelCatalogService = modelCatalogService;
        this.llmGateway = llmGateway;
    }

    @GetMapping("/api/model-configs")
    public ApiResponse<List<ModelConfigResponse>> listConfigs(@RequestParam String role) {
        return ApiResponse.ok(modelConfigService.list(parseRole(role)).stream()
                .map(ModelConfigResponse::from)
                .toList());
    }

    @GetMapping("/api/model-configs/active")
    public ApiResponse<ActiveModelConfigsResponse> activeConfigs() {
        return ApiResponse.ok(new ActiveModelConfigsResponse(
                ModelConfigResponse.from(modelConfigService.activeChatOrDefault()),
                ModelConfigResponse.from(modelConfigService.activeEmbeddingOrDefault())
        ));
    }

    @PostMapping("/api/model-configs")
    public ApiResponse<ModelConfigResponse> createConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.create(request)));
    }

    @PutMapping("/api/model-configs/{id}")
    public ApiResponse<ModelConfigResponse> updateConfig(
            @PathVariable String id,
            @Valid @RequestBody ModelConfigRequest request
    ) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.update(id, request)));
    }

    @DeleteMapping("/api/model-configs/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable String id) {
        modelConfigService.delete(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/model-configs/{id}/activate")
    public ApiResponse<ModelConfigResponse> activateConfig(@PathVariable String id) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.activate(id)));
    }

    @PostMapping("/api/model-configs/test")
    public ApiResponse<ModelConfigTestResponse> testRoleConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(testConfigByRole(modelConfigService.connectionTestConfig(request)));
    }

    @PostMapping("/api/model-configs/models")
    public ApiResponse<ModelOptionsResponse> fetchRoleModels(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelCatalogService.fetchModels(request));
    }

    @GetMapping("/api/model-config")
    public ApiResponse<LegacyModelConfigResponse> getConfig() {
        return ApiResponse.ok(LegacyModelConfigResponse.from(
                modelConfigService.activeChatOrDefault(),
                modelConfigService.activeEmbeddingOrDefault()
        ));
    }

    @PutMapping("/api/model-config")
    public ApiResponse<ModelConfigResponse> saveConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.save(request)));
    }

    @PostMapping("/api/model-config/test")
    public ApiResponse<ModelConfigTestResponse> testConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(testConfigByRole(modelConfigService.connectionTestConfig(request)));
    }

    @PostMapping("/api/model-config/models")
    public ApiResponse<ModelOptionsResponse> fetchModels(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelCatalogService.fetchModels(request));
    }

    private static ModelConfigRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new ModelConfigurationException("Invalid role: role is required. Must be CHAT or EMBEDDING.");
        }
        try {
            return ModelConfigRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ModelConfigurationException("Invalid role: " + role + ". Must be CHAT or EMBEDDING.");
        }
    }

    private ModelConfigTestResponse testConfigByRole(ModelConfig config) {
        if (config.role() == ModelConfigRole.CHAT) {
            llmGateway.testConnection(config);
            return new ModelConfigTestResponse(true, "模型连接测试成功");
        }
        // Embedding 连接会在 /models 或索引流程里验证。部分服务商没有轻量 embedding test，
        // 这里不主动发 embedding 请求，避免测试连接产生额外计费或维度副作用。
        return new ModelConfigTestResponse(true, "Embedding 配置格式已校验；未发起向量调用，请通过获取模型或重建索引验证服务端连接。");
    }
}


