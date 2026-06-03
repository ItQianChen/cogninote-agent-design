package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.LlmGateway;
import com.itqianchen.agentdesign.domain.chat.RagChatStream;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.domain.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.dto.search.SearchHitResponse;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.search.SearchRequest;
import com.itqianchen.agentdesign.dto.search.SearchResponse;
import com.itqianchen.agentdesign.domain.search.StoredChunk;
import com.itqianchen.agentdesign.dto.chat.ChatStreamRequest;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class RagChatService {

    private static final int MAX_CONTEXT_CHARS = 12000;

    private final KnowledgeStore knowledgeStore;
    private final DocumentRepository documentRepository;
    private final ModelConfigService modelConfigService;
    private final LlmGateway llmGateway;
    private final ChatPromptProperties promptProperties;

    public RagChatService(
            KnowledgeStore knowledgeStore,
            DocumentRepository documentRepository,
            ModelConfigService modelConfigService,
            LlmGateway llmGateway,
            ChatPromptProperties promptProperties
    ) {
        this.knowledgeStore = knowledgeStore;
        this.documentRepository = documentRepository;
        this.modelConfigService = modelConfigService;
        this.llmGateway = llmGateway;
        this.promptProperties = promptProperties;
    }

    public RagChatStream stream(ChatStreamRequest request) {
        ModelConfig config = modelConfigService.requireActiveChatConfigured();
        String question = request.question().trim();
        int topK = normalizeTopK(request.topK(), config.resolvedDefaultTopK());
        SearchMode requestedMode = request.mode() == null ? SearchMode.HYBRID : request.mode();
        SearchResponse searchResponse = searchWithFallback(question, requestedMode, topK);
        List<RagSourceResponse> sources = hydrateSources(toSources(searchResponse.hits()));
        Prompt prompt = buildPrompt(question, sources);

        return new RagChatStream(
                UUID.randomUUID().toString(),
                searchResponse.mode(),
                sources,
                llmGateway.stream(config, prompt)
        );
    }

    public Prompt buildPrompt(String question, List<RagSourceResponse> sources) {
        String systemPrompt = promptProperties.rag().system();
        String userPrompt = renderRagUserPrompt(question, buildContext(sources));

        return new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
    }

    private String renderRagUserPrompt(String question, String context) {
        // 提示词模板放在配置文件中维护，这里只负责注入运行时变量。
        return promptProperties.rag().user()
                .replace("{question}", question)
                .replace("{context}", context);
    }

    private SearchResponse searchWithFallback(String question, SearchMode requestedMode, int topK) {
        try {
            return knowledgeStore.search(new SearchRequest(question, requestedMode, topK));
        } catch (EmbeddingUnavailableException ex) {
            if (requestedMode == SearchMode.HYBRID || requestedMode == SearchMode.VECTOR) {
                // Embedding 未配置时，RAG 仍应尽量基于本地文本回答。
                // 降级后的实际检索模式会通过 meta 事件告诉前端，方便用户判断答案质量。
                return knowledgeStore.search(new SearchRequest(question, SearchMode.KEYWORD, topK));
            }
            throw ex;
        }
    }

    private List<RagSourceResponse> toSources(List<SearchHitResponse> hits) {
        List<RagSourceResponse> sources = new ArrayList<>();
        for (int index = 0; index < hits.size(); index++) {
            sources.add(RagSourceResponse.from(index + 1, hits.get(index)));
        }
        return sources;
    }

    private List<RagSourceResponse> hydrateSources(List<RagSourceResponse> sources) {
        if (sources.isEmpty()) {
            return sources;
        }

        Map<String, StoredChunk> chunksById = documentRepository.findStoredChunksByIds(sources.stream()
                        .map(RagSourceResponse::chunkId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(StoredChunk::chunkId, chunk -> chunk));

        return sources.stream()
                .map(source -> {
                    StoredChunk chunk = chunksById.get(source.chunkId());
                    if (chunk == null) {
                        return source;
                    }
                    return source.withContent(chunk.content());
                })
                .toList();
    }

    private String buildContext(List<RagSourceResponse> sources) {
        if (sources.isEmpty()) {
            return promptProperties.rag().emptyContext();
        }

        StringBuilder builder = new StringBuilder();
        for (RagSourceResponse source : sources) {
            String location = source.heading() != null && !source.heading().isBlank()
                    ? source.heading()
                    : source.pageNumber() == null ? "无标题" : "第 " + source.pageNumber() + " 页";
            String block = """
                    [%d] 文件：%s
                    路径：%s
                    位置：%s
                    内容：%s

                    """.formatted(
                    source.index(),
                    source.fileName(),
                    source.sourcePath(),
                    location,
                    source.content() == null || source.content().isBlank() ? source.preview() : source.content()
            );
            if (builder.length() + block.length() > MAX_CONTEXT_CHARS) {
                // 控制 Prompt 上下文长度，避免长文档把模型窗口挤满，后续可替换为更精细的预算策略。
                break;
            }
            builder.append(block);
        }
        return builder.toString();
    }

    private static int normalizeTopK(Integer requestedTopK, int configuredTopK) {
        int value = requestedTopK == null ? configuredTopK : requestedTopK;
        return Math.clamp(value, 1, 50);
    }
}


