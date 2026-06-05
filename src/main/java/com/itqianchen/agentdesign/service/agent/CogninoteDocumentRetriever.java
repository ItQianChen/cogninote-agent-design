package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

public final class CogninoteDocumentRetriever implements DocumentRetriever {

    private final KnowledgeContextProvider knowledgeContextProvider;
    private final String originalQuestion;
    private final SearchMode requestedMode;
    private final int topK;
    private volatile KnowledgeContext cachedContext;

    public CogninoteDocumentRetriever(
            KnowledgeContextProvider knowledgeContextProvider,
            String originalQuestion,
            SearchMode requestedMode,
            int topK
    ) {
        this.knowledgeContextProvider = knowledgeContextProvider;
        this.originalQuestion = originalQuestion;
        this.requestedMode = requestedMode;
        this.topK = topK;
    }

    public KnowledgeContext retrieveKnowledgeContext() {
        return context();
    }

    @Override
    public List<Document> retrieve(Query query) {
        return context().sources().stream()
                .map(CogninoteDocumentRetriever::toDocument)
                .toList();
    }

    private KnowledgeContext context() {
        KnowledgeContext existing = cachedContext;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (cachedContext == null) {
                /*
                 * ChatClient 的 user(...) 里包含格式化提示词，不适合直接作为检索 query。
                 * DocumentRetriever 仍挂在 Spring AI RAG Advisor 链上，但检索问题固定使用用户原始输入。
                 */
                cachedContext = knowledgeContextProvider.retrieve(originalQuestion, requestedMode, topK);
            }
            return cachedContext;
        }
    }

    private static Document toDocument(RagSourceResponse source) {
        return Document.builder()
                .id(source.chunkId())
                .text(documentText(source))
                .metadata(metadata(source))
                .score(source.score())
                .build();
    }

    private static String documentText(RagSourceResponse source) {
        String content = source.content() == null || source.content().isBlank()
                ? source.preview()
                : source.content();
        String location = source.heading() != null && !source.heading().isBlank()
                ? source.heading()
                : source.pageNumber() == null ? "无标题" : "第 " + source.pageNumber() + " 页";
        /*
         * 交给 Spring AI RAG Advisor 的 Document 也保留引用编号和路径信息。
         * 这样模型能按前端 meta 的 [1]/[2] 编号回应，而不是只看到裸文本片段。
         */
        return """
                [%d] 文件：%s
                路径：%s
                位置：%s
                内容：%s
                """.formatted(
                source.index(),
                source.fileName(),
                source.sourcePath(),
                location,
                content == null ? "" : content
        );
    }

    private static Map<String, Object> metadata(RagSourceResponse source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sourceIndex", source.index());
        metadata.put("score", source.score());
        /*
         * Spring AI Document 会拒绝 null metadata value。
         * heading/pageNumber 这类来源字段本来就是可选的，缺失时应省略 metadata，
         * 不能把 null 透传到 RetrievalAugmentationAdvisor 链路里。
         */
        putIfNotNull(metadata, "chunkId", source.chunkId());
        putIfNotNull(metadata, "documentId", source.documentId());
        putIfNotNull(metadata, "fileName", source.fileName());
        putIfNotNull(metadata, "sourcePath", source.sourcePath());
        putIfNotNull(metadata, "heading", source.heading());
        putIfNotNull(metadata, "pageNumber", source.pageNumber());
        return metadata;
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
