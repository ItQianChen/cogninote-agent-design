package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.chat.LlmGateway;
import com.itqianchen.agentdesign.domain.chat.RagChatStream;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.domain.search.StoredChunk;
import com.itqianchen.agentdesign.dto.chat.ChatStreamRequest;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import com.itqianchen.agentdesign.dto.index.IndexStatusResponse;
import com.itqianchen.agentdesign.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.dto.search.SearchHitResponse;
import com.itqianchen.agentdesign.dto.search.SearchRequest;
import com.itqianchen.agentdesign.dto.search.SearchResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.service.chat.RagChatService;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class RagChatServiceTests {

    @Test
    void promptContainsQuestionContextAndSourceNumbers() {
        RagChatService service = newService(new FakeKnowledgeStore(false), new FakeLlmGateway());
        List<RagSourceResponse> sources = List.of(RagSourceResponse.from(1, hit("chunk-1")));

        Prompt prompt = service.buildPrompt("如何打包？", sources);

        assertThat(prompt.getContents())
                .contains("如何打包？")
                .contains("[1]")
                .contains("packaging.md")
                .contains("Launch4j");
    }

    @Test
    void promptUsesConfiguredTemplates() {
        ChatPromptProperties promptProperties = new ChatPromptProperties(
                new ChatPromptProperties.Rag(
                        "自定义系统提示词",
                        "Q={question}\nCTX={context}",
                        "自定义空上下文"
                ),
                new ChatPromptProperties.ConnectionTest("测试连接")
        );
        RagChatService service = newService(new FakeKnowledgeStore(false), new FakeLlmGateway(), promptProperties);

        Prompt prompt = service.buildPrompt("没有资料的问题", List.of());

        assertThat(prompt.getContents())
                .contains("自定义系统提示词")
                .contains("Q=没有资料的问题")
                .contains("CTX=自定义空上下文");
    }

    @Test
    void hybridFallsBackToKeywordWhenEmbeddingIsUnavailable() {
        FakeKnowledgeStore knowledgeStore = new FakeKnowledgeStore(true);
        RagChatService service = newService(knowledgeStore, new FakeLlmGateway());

        RagChatStream stream = service.stream(new ChatStreamRequest("Launch4j 是什么？", 5, SearchMode.HYBRID));

        assertThat(stream.retrievalMode()).isEqualTo(SearchMode.KEYWORD);
        assertThat(stream.sources()).hasSize(1);
        assertThat(stream.answer().collectList().block()).containsExactly("答案片段");
        assertThat(knowledgeStore.seenModes).containsExactly(SearchMode.HYBRID, SearchMode.KEYWORD);
    }

    private static RagChatService newService(KnowledgeStore knowledgeStore, LlmGateway llmGateway) {
        return newService(knowledgeStore, llmGateway, defaultPromptProperties());
    }

    private static RagChatService newService(
            KnowledgeStore knowledgeStore,
            LlmGateway llmGateway,
            ChatPromptProperties promptProperties
    ) {
        ModelConfigRepository repository = new ModelConfigRepository(null) {
            @Override
            public Optional<ModelConfig> findActive(ModelConfigRole role) {
                long now = System.currentTimeMillis();
                return Optional.of(new ModelConfig(
                        ModelConfigDefaults.ACTIVE_CHAT_CONFIG_ID,
                        ModelConfigRole.CHAT,
                        ModelConfigDefaults.PROVIDER,
                        ModelConfigDefaults.DISPLAY_NAME,
                        ModelConfigDefaults.BASE_URL,
                        "sk-test",
                        ModelConfigDefaults.CHAT_MODEL,
                        null,
                        ModelConfigDefaults.TEMPERATURE,
                        ModelConfigDefaults.TOP_K,
                        true,
                        now,
                        now
                ));
            }
        };
        return new RagChatService(
                knowledgeStore,
                new FakeDocumentRepository(),
                new ModelConfigService(repository),
                llmGateway,
                promptProperties
        );
    }

    private static ChatPromptProperties defaultPromptProperties() {
        return new ChatPromptProperties(
                new ChatPromptProperties.Rag(
                        """
                                你是 CogniNote Agent 的本地知识库问答助手。
                                你必须只基于提供的知识库上下文回答，不要编造未出现的信息。
                                如果上下文不足以回答，明确说明：当前知识库中没有足够依据。
                                回答中必须用 [1]、[2] 这样的编号标注引用来源。
                                """,
                        """
                                用户问题：
                                {question}

                                知识库上下文：
                                {context}

                                请给出简洁、可验证的中文回答。
                                """,
                        "没有检索到相关知识库片段。"
                ),
                new ChatPromptProperties.ConnectionTest("请用一句话回答：CogniNote 连接测试是否可用？")
        );
    }

    private static SearchHitResponse hit(String chunkId) {
        return new SearchHitResponse(
                chunkId,
                "doc-1",
                "packaging.md",
                "D:/notes/packaging.md",
                "打包",
                null,
                "CogniNote 使用 Launch4j 生成 Windows EXE。",
                1.0,
                1.0,
                null
        );
    }

    private static class FakeKnowledgeStore implements KnowledgeStore {
        private final boolean failHybrid;
        private final List<SearchMode> seenModes = new ArrayList<>();

        private FakeKnowledgeStore(boolean failHybrid) {
            this.failHybrid = failHybrid;
        }

        @Override
        public void indexDocument(IndexedDocument document) {
        }

        @Override
        public void deleteByDocumentId(String documentId) {
        }

        @Override
        public SearchResponse search(SearchRequest request) {
            seenModes.add(request.modeOrDefault());
            if (failHybrid && request.modeOrDefault() == SearchMode.HYBRID) {
                throw new EmbeddingUnavailableException("hybrid search is unavailable");
            }
            return new SearchResponse(request.query(), request.modeOrDefault(), List.of(hit("chunk-1")));
        }

        @Override
        public IndexStatusResponse status() {
            return null;
        }

        @Override
        public RebuildIndexResponse rebuildAll() {
            return null;
        }
    }

    private static class FakeLlmGateway implements LlmGateway {
        @Override
        public Flux<String> stream(ModelConfig config, Prompt prompt) {
            return Flux.just("答案片段");
        }

        @Override
        public void testConnection(ModelConfig config) {
        }
    }

    private static class FakeDocumentRepository extends DocumentRepository {

        private FakeDocumentRepository() {
            super(null);
        }

        @Override
        public List<StoredChunk> findStoredChunksByIds(List<String> chunkIds) {
            return chunkIds.stream()
                    .map(chunkId -> new StoredChunk(
                            chunkId,
                            "doc-1",
                            0,
                            "CogniNote 使用 Launch4j 生成 Windows EXE。",
                            "hash",
                            null,
                            "打包",
                            "packaging.md",
                            "D:/notes/packaging.md"
                    ))
                    .toList();
        }
    }
}
