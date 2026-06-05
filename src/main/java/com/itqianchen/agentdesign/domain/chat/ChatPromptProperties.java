package com.itqianchen.agentdesign.domain.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.prompts")
public record ChatPromptProperties(
        Rag rag,
        ConnectionTest connectionTest
) {

    public ChatPromptProperties {
        if (rag == null) {
            throw new IllegalArgumentException("app.chat.prompts.rag must be configured");
        }
        if (connectionTest == null) {
            throw new IllegalArgumentException("app.chat.prompts.connection-test must be configured");
        }
    }

    public record Rag(
            String system,
            String user,
            String emptyContext
    ) {

        public Rag {
            requireText(system, "app.chat.prompts.rag.system");
            requireText(user, "app.chat.prompts.rag.user");
            requireText(emptyContext, "app.chat.prompts.rag.empty-context");
            /*
             * 第十三阶段后知识库上下文由 Spring AI RAG Advisor 注入，
             * user 模板只负责承载当前用户问题，不能再要求 {context} 占位符。
             */
            if (!user.contains("{question}")) {
                throw new IllegalArgumentException(
                        "app.chat.prompts.rag.user must contain {question}");
            }
            if (user.contains("{context}")) {
                throw new IllegalArgumentException(
                        "app.chat.prompts.rag.user must not contain {context}; RAG context is injected by Spring AI Advisor");
            }
        }
    }

    public record ConnectionTest(String user) {

        public ConnectionTest {
            requireText(user, "app.chat.prompts.connection-test.user");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
    }
}
