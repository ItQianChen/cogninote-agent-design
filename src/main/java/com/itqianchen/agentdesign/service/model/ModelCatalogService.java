package com.itqianchen.agentdesign.service.model;


import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.itqianchen.agentdesign.domain.enums.model.ModelCapability;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.domain.dto.model.ModelOptionResponse;
import com.itqianchen.agentdesign.domain.dto.model.ModelOptionsResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 从模型供应商拉取可选模型列表。
 *
 * <p>该服务会访问外部 /models 接口，调用前必须先按连接测试规则补齐配置，避免空地址或错误 role
 * 产生不可解释的第三方请求。</p>
 */
@Service
public class ModelCatalogService {

    private static final List<String> EMBEDDING_HINTS = List.of(
            "embedding",
            "embed",
            "bge",
            "gte",
            "e5",
            "jina-embeddings"
    );
    private static final List<String> CHAT_HINTS = List.of(
            "chat",
            "completion",
            "text-generation"
    );
    private static final List<String> VISION_HINTS = List.of(
            "vision",
            "visual",
            "multimodal",
            "vl",
            "omni"
    );
    private static final List<String> CAPABILITY_FIELD_NAMES = List.of(
            "type",
            "sub_type",
            "subType",
            "task",
            "capability",
            "capabilities",
            "modalities",
            "features"
    );

    private final ModelConfigService modelConfigService;
    private final RestClient restClient;

    /**
     * 注入模型配置服务和 RestClient 构造器。
     *
     * @param modelConfigService 模型配置服务
     * @param restClientBuilder RestClient 构造器
     */
    public ModelCatalogService(ModelConfigService modelConfigService, RestClient.Builder restClientBuilder) {
        this.modelConfigService = modelConfigService;
        this.restClient = restClientBuilder.build();
    }

    /**
     * 从 provider 的 /models 接口拉取模型列表。
     *
     * <p>request 会先按连接测试配置规则补齐默认值，避免空 baseUrl 或 role 直接打到外部服务。</p>
     *
     * @param request 临时模型配置请求
     * @return 模型选项响应
     */
    public ModelOptionsResponse fetchModels(ModelConfigRequest request) {
        ModelConfig config = modelConfigService.connectionTestConfig(request);

        try {
            JsonNode response = restClient.get()
                    .uri(modelsUri(config))
                    .header("Authorization", "Bearer " + config.apiKey())
                    .retrieve()
                    .body(JsonNode.class);
            return new ModelOptionsResponse(parseModels(response), System.currentTimeMillis());
        } catch (RestClientException ex) {
            throw new ModelConfigurationException("Failed to fetch model list: " + ex.getMessage(), ex);
        }
    }

    /**
     * 选择 Provider 的模型列表接口地址。
     *
     * @param config 已归一化的模型配置
     * @return /models URI
     */
    private static java.net.URI modelsUri(ModelConfig config) {
        return OpenAiCompatibleUrls.modelsUri(config.baseUrl());
    }

    /**
     * 解析 OpenAI-compatible 的 data[] 模型列表。
     *
     * @param response /models JSON 响应
     * @return 模型选项列表
     */
    private static List<ModelOptionResponse> parseModels(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray()) {
            throw new ModelConfigurationException("Model list response does not contain data[]");
        }

        return StreamSupport.stream(data.spliterator(), false)
                .map(ModelCatalogService::toModelOption)
                .filter(option -> option.id() != null && !option.id().isBlank())
                .sorted(Comparator.comparing(ModelOptionResponse::capability)
                        .thenComparing(ModelOptionResponse::id))
                .toList();
    }

    /**
     * 将单个 JSON 节点转换为模型选项。
     *
     * @param node 模型 JSON 节点
     * @return 模型选项
     */
    private static ModelOptionResponse toModelOption(JsonNode node) {
        String id = text(node, "id");
        String name = text(node, "name");
        if (name.isBlank()) {
            name = id;
        }
        return new ModelOptionResponse(id, name, classify(node, id));
    }

    /**
     * 读取 JSON 文本字段。
     *
     * @param node JSON 节点
     * @param fieldName 字段名
     * @return 去空白后的文本
     */
    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    /**
     * /models 没有统一的能力字段，只能用 provider 字段和模型 ID 做保守标注。
     *
     * @param node 模型 JSON 节点
     * @param modelId 模型 ID
     * @return 模型能力分类
     */
    private static ModelCapability classify(JsonNode node, String modelId) {
        if (containsAnyCapabilityField(node, EMBEDDING_HINTS)) {
            return ModelCapability.EMBEDDING;
        }
        if (containsAnyCapabilityField(node, CHAT_HINTS)) {
            return ModelCapability.CHAT;
        }
        if (containsAnyCapabilityField(node, VISION_HINTS)) {
            return ModelCapability.VISION;
        }
        if (modelId == null || modelId.isBlank()) {
            return ModelCapability.UNKNOWN;
        }
        String normalized = modelId.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, EMBEDDING_HINTS)) {
            return ModelCapability.EMBEDDING;
        }
        if (containsAny(normalized, VISION_HINTS)) {
            return ModelCapability.VISION;
        }
        return ModelCapability.UNKNOWN;
    }

    /**
     * 检查 provider 返回的能力字段是否包含目标关键词。
     *
     * @param node 模型 JSON 节点
     * @param hints 能力关键词
     * @return 任一能力字段命中时为 true
     */
    private static boolean containsAnyCapabilityField(JsonNode node, List<String> hints) {
        if (node == null || node.isNull()) {
            return false;
        }
        return CAPABILITY_FIELD_NAMES.stream()
                .map(node::get)
                .anyMatch(value -> jsonContainsAny(value, hints));
    }

    /**
     * 递归读取 provider 私有字段。
     *
     * <p>不同 OpenAI-compatible 服务会把能力放在字符串、数组或对象中；只用于标签和排序，
     * 不作为调用正确性的最终判断。</p>
     *
     * @param node 字段节点
     * @param hints 能力关键词
     * @return 任一文本节点命中时为 true
     */
    private static boolean jsonContainsAny(JsonNode node, List<String> hints) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isTextual()) {
            return containsAny(node.asText("").toLowerCase(Locale.ROOT), hints);
        }
        if (node.isArray() || node.isObject()) {
            return StreamSupport.stream(node.spliterator(), false)
                    .anyMatch(child -> jsonContainsAny(child, hints));
        }
        return false;
    }

    private static boolean containsAny(String value, List<String> hints) {
        return hints.stream().anyMatch(value::contains);
    }
}
