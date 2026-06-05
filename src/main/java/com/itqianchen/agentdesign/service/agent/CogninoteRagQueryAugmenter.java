package com.itqianchen.agentdesign.service.agent;

import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;

public final class CogninoteRagQueryAugmenter implements QueryAugmenter {

    private final String emptyContextPrompt;

    public CogninoteRagQueryAugmenter(String emptyContextPrompt) {
        this.emptyContextPrompt = emptyContextPrompt;
    }

    @Override
    public Query augment(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new Query("""
                    用户问题：
                    %s

                    知识库检索结果：
                    %s
                    """.formatted(query.text(), emptyContextPrompt));
        }

        return new Query("""
                用户问题：
                %s

                以下内容由 Spring AI RAG Advisor 注入，回答必须优先依据这些知识库片段：
                %s

                请严格遵守系统消息中的 Markdown 格式要求，并用 [1]、[2] 这样的编号标注引用来源。
                """.formatted(query.text(), formatDocuments(documents)));
    }

    private static String formatDocuments(List<Document> documents) {
        StringBuilder builder = new StringBuilder();
        for (Document document : documents) {
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(document.getText());
        }
        return builder.toString();
    }
}
