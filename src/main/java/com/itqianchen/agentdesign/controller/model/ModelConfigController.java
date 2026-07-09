package com.itqianchen.agentdesign.controller.model;


import com.itqianchen.agentdesign.common.api.ApiResponse;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.dto.model.ActiveModelConfigsResponse;
import com.itqianchen.agentdesign.domain.dto.model.LegacyModelConfigResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigSettingsResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigTestResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigUpsertRequest;
import com.itqianchen.agentdesign.domain.dto.model.ModelOptionsResponse;
import com.itqianchen.agentdesign.service.model.ModelCatalogService;
import com.itqianchen.agentdesign.service.model.ModelConnectionTestService;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ModelConfigController 暴露模型配置相关 HTTP 接口。
 * <p>控制器只负责请求参数、响应包装和服务层委派，避免承载业务细节。</p>
 */
@RestController
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelCatalogService modelCatalogService;
    private final ModelConnectionTestService modelConnectionTestService;

    /**
     * 注入模型配置、模型目录和连接测试服务。
     *
     * @param modelConfigService 模型配置持久化服务
     * @param modelCatalogService 提供商模型列表查询服务
     * @param modelConnectionTestService 临时配置连通性测试服务
     */
    public ModelConfigController(
            ModelConfigService modelConfigService,
            ModelCatalogService modelCatalogService,
            ModelConnectionTestService modelConnectionTestService
    ) {
        this.modelConfigService = modelConfigService;
        this.modelCatalogService = modelCatalogService;
        this.modelConnectionTestService = modelConnectionTestService;
    }

    /**
     * 按角色列出模型配置。
     *
     * <p>role 非法时提前转换为统一配置错误，避免前端把空列表误判为未配置。</p>
     */
    @GetMapping("/api/model-configs")
    public ApiResponse<List<ModelConfigResponse>> listConfigs(@RequestParam String role) {
        return ApiResponse.ok(modelConfigService.list(parseRole(role)).stream()
                .map(ModelConfigResponse::from)
                .toList());
    }

    /**
     * 返回当前激活的对话模型、向量模型和视觉模型配置。
     * <p>该接口服务于前端顶部状态和运行前置检查。</p>
     */
    @GetMapping("/api/model-configs/active")
    public ApiResponse<ActiveModelConfigsResponse> activeConfigs() {
        return ApiResponse.ok(new ActiveModelConfigsResponse(
                ModelConfigResponse.from(modelConfigService.activeChatOrDefault()),
                ModelConfigResponse.from(modelConfigService.activeEmbeddingOrDefault()),
                ModelConfigResponse.from(modelConfigService.activeVisionOrDefault())
        ));
    }

    /**
     * 构建模型设置页使用的配置快照。
     * <p>快照同时包含列表、选中项和当前激活项，便于前端一次性渲染。</p>
     */
    @GetMapping("/api/model-configs/settings")
    public ApiResponse<ModelConfigSettingsResponse> settingsSnapshot(@RequestParam String role) {
        return ApiResponse.ok(modelConfigService.settingsSnapshot(parseRole(role)));
    }

    /**
     * 在设置页创建模型配置并返回新的页面快照。
     *
     * <p>返回整页快照可让前端一次性同步列表、选中项和激活项，减少并发状态漂移。</p>
     *
     * @param request 新模型配置
     * @return 更新后的设置页快照
     */
    @PostMapping("/api/model-configs/settings/configs")
    public ApiResponse<ModelConfigSettingsResponse> createSettingsConfig(
            @Valid @RequestBody ModelConfigUpsertRequest request
    ) {
        return ApiResponse.ok(modelConfigService.createSettings(request));
    }

    /**
     * 在设置页更新模型配置并返回新的页面快照。
     *
     * @param id 配置 ID
     * @param request 更新后的配置内容
     * @return 更新后的设置页快照
     */
    @PutMapping("/api/model-configs/settings/configs/{id}")
    public ApiResponse<ModelConfigSettingsResponse> updateSettingsConfig(
            @PathVariable String id,
            @Valid @RequestBody ModelConfigUpsertRequest request
    ) {
        return ApiResponse.ok(modelConfigService.updateSettings(id, request));
    }

    /**
     * 删除设置页中的模型配置。
     *
     * <p>服务层会保证每个角色仍有激活配置；否则删除会以配置错误失败。</p>
     *
     * @param id 配置 ID
     * @return 更新后的设置页快照
     */
    @DeleteMapping("/api/model-configs/settings/configs/{id}")
    public ApiResponse<ModelConfigSettingsResponse> deleteSettingsConfig(@PathVariable String id) {
        return ApiResponse.ok(modelConfigService.deleteSettings(id));
    }

    /**
     * 激活设置页中的模型配置。
     *
     * <p>同一角色只能有一个激活配置，旧激活项由服务层在事务内切换。</p>
     *
     * @param id 配置 ID
     * @return 更新后的设置页快照
     */
    @PostMapping("/api/model-configs/settings/configs/{id}/activate")
    public ApiResponse<ModelConfigSettingsResponse> activateSettingsConfig(@PathVariable String id) {
        return ApiResponse.ok(modelConfigService.activateSettings(id));
    }

    /**
     * 兼容旧调用方创建模型配置。
     *
     * @param request 模型配置请求
     * @return 新配置
     */
    @PostMapping("/api/model-configs")
    public ApiResponse<ModelConfigResponse> createConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.create(request)));
    }

    /**
     * 兼容旧调用方更新模型配置。
     *
     * @param id 配置 ID
     * @param request 更新后的配置内容
     * @return 更新后的配置
     */
    @PutMapping("/api/model-configs/{id}")
    public ApiResponse<ModelConfigResponse> updateConfig(
            @PathVariable String id,
            @Valid @RequestBody ModelConfigRequest request
    ) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.update(id, request)));
    }

    /**
     * 兼容旧调用方删除模型配置。
     *
     * <p>删除后不会返回设置页快照，旧接口调用方需要自行刷新。</p>
     *
     * @param id 配置 ID
     * @return 空响应
     */
    @DeleteMapping("/api/model-configs/{id}")
    public ApiResponse<Void> deleteConfig(@PathVariable String id) {
        modelConfigService.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * 兼容旧调用方激活模型配置。
     *
     * @param id 配置 ID
     * @return 激活后的配置
     */
    @PostMapping("/api/model-configs/{id}/activate")
    public ApiResponse<ModelConfigResponse> activateConfig(@PathVariable String id) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.activate(id)));
    }

    /**
     * 使用请求中的临时配置验证当前角色模型，不持久化到本地 SQLite。
     *
     * @param request 临时模型配置
     * @return 连接测试结果
     */
    @PostMapping("/api/model-configs/test")
    public ApiResponse<ModelConfigTestResponse> testRoleConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelConnectionTestService.test(modelConfigService.connectionTestConfig(request)));
    }

    /**
     * 查询当前角色可选模型列表。
     *
     * <p>该接口会触达模型提供商，失败时返回配置错误，用于设置页下拉选项。</p>
     *
     * @param request 当前角色的临时配置
     * @return 提供商返回的模型选项
     */
    @PostMapping("/api/model-configs/models")
    public ApiResponse<ModelOptionsResponse> fetchRoleModels(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelCatalogService.fetchModels(request));
    }

    /**
     * 读取旧版单页模型配置。
     *
     * <p>响应同时包含对话和向量配置，保持旧前端接口形态。</p>
     *
     * @return 旧版配置响应
     */
    @GetMapping("/api/model-config")
    public ApiResponse<LegacyModelConfigResponse> getConfig() {
        return ApiResponse.ok(LegacyModelConfigResponse.from(
                modelConfigService.activeChatOrDefault(),
                modelConfigService.activeEmbeddingOrDefault()
        ));
    }

    /**
     * 保存旧版单页模型配置。
     *
     * <p>旧接口会把一个请求拆分为对话和向量配置，服务层负责兼容字段归属。</p>
     *
     * @param request 旧版模型配置请求
     * @return 保存后的对话配置
     */
    @PutMapping("/api/model-config")
    public ApiResponse<ModelConfigResponse> saveConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(ModelConfigResponse.from(modelConfigService.save(request)));
    }

    /**
     * 兼容旧模型设置接口的连接测试，不持久化请求中的临时配置。
     *
     * @param request 临时模型配置
     * @return 连接测试结果
     */
    @PostMapping("/api/model-config/test")
    public ApiResponse<ModelConfigTestResponse> testConfig(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelConnectionTestService.test(modelConfigService.connectionTestConfig(request)));
    }

    /**
     * 兼容旧模型设置接口的模型列表查询。
     *
     * <p>返回结构保持旧前端可消费的 options 形态，新设置页走按角色区分的新接口。</p>
     *
     * @param request 临时模型配置
     * @return 模型选项列表
     */
    @PostMapping("/api/model-config/models")
    public ApiResponse<ModelOptionsResponse> fetchModels(@Valid @RequestBody ModelConfigRequest request) {
        return ApiResponse.ok(modelCatalogService.fetchModels(request));
    }

    /**
     * 解析并校验模型配置角色。
     * <p>只允许 {@code CHAT}、{@code EMBEDDING} 和 {@code VISION}，非法值会提前转为配置异常。</p>
     */
    private static ModelConfigRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new ModelConfigurationException("Invalid role: role is required. Must be CHAT, EMBEDDING or VISION.");
        }
        try {
            return ModelConfigRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ModelConfigurationException("Invalid role: " + role + ". Must be CHAT, EMBEDDING or VISION.");
        }
    }

}


