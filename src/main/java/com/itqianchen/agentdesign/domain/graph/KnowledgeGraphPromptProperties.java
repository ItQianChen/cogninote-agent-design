package com.itqianchen.agentdesign.domain.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识图谱 Prompt 配置。
 * <p>Prompt 文本和版本号都来自配置文件，避免模型规则散落在服务代码里。</p>
 */
@ConfigurationProperties(prefix = "app.knowledge-graph.prompts")
public record KnowledgeGraphPromptProperties(
        Extraction extraction
) {

    public KnowledgeGraphPromptProperties {
        if (extraction == null) {
            throw new IllegalArgumentException("app.knowledge-graph.prompts.extraction must be configured");
        }
    }

    /**
     * 单 chunk 抽取 Prompt。
     * <p>version 会参与抽取缓存 key；修改抽取语义时应同步升级版本。</p>
     */
    public record Extraction(
            String version,
            String system,
            String user
    ) {

        public Extraction {
            requireText(version, "app.knowledge-graph.prompts.extraction.version");
            requireText(system, "app.knowledge-graph.prompts.extraction.system");
            requireText(user, "app.knowledge-graph.prompts.extraction.user");
            requirePlaceholder(user, "app.knowledge-graph.prompts.extraction.user", "{documentName}");
            requirePlaceholder(user, "app.knowledge-graph.prompts.extraction.user", "{chunkId}");
            requirePlaceholder(user, "app.knowledge-graph.prompts.extraction.user", "{heading}");
            requirePlaceholder(user, "app.knowledge-graph.prompts.extraction.user", "{pageNumber}");
            requirePlaceholder(user, "app.knowledge-graph.prompts.extraction.user", "{content}");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
    }

    private static void requirePlaceholder(String value, String propertyName, String placeholder) {
        if (!value.contains(placeholder)) {
            throw new IllegalArgumentException(propertyName + " must contain " + placeholder);
        }
    }
}
