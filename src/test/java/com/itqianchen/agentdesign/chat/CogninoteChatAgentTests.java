package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.ai.AiEmbeddingRuntime;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.domain.search.StoredChunk;
import com.itqianchen.agentdesign.dto.index.IndexStatusResponse;
import com.itqianchen.agentdesign.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.dto.search.SearchHitResponse;
import com.itqianchen.agentdesign.dto.search.SearchRequest;
import com.itqianchen.agentdesign.dto.search.SearchResponse;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionMapper;
import com.itqianchen.agentdesign.mapper.model.ModelConfigMapper;
import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import com.itqianchen.agentdesign.metadata.DatabaseSchemaInitializer;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.service.agent.CogninoteChatAgent;
import com.itqianchen.agentdesign.service.agent.CogninoteDocumentRetriever;
import com.itqianchen.agentdesign.service.agent.KnowledgeContextProvider;
import com.itqianchen.agentdesign.service.agent.PromptAssembler;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshotService;
import com.itqianchen.agentdesign.service.chat.RagSourcesJsonCodec;
import com.itqianchen.agentdesign.service.chat.TokenEstimator;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

class CogninoteChatAgentTests {

    @Test
    void promptAssemblerNoLongerRequiresManualContextPlaceholder() {
        PromptAssembler promptAssembler = new PromptAssembler(defaultPromptProperties());

        assertThat(promptAssembler.systemPrompt()).contains("Markdown");
        assertThat(promptAssembler.userPrompt("如何打包？"))
                .contains("如何打包？")
                .doesNotContain("{context}");
        assertThat(promptAssembler.emptyContextPrompt()).contains("没有检索到");
    }

    @Test
    void hybridFallsBackToKeywordWhenEmbeddingIsUnavailable() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(true), Flux.just("答案片段"));

        AgentChatStream stream = fixture.agent.stream(new AgentRequest(
                "request-1",
                "Launch4j 是什么？",
                5,
                SearchMode.HYBRID,
                "conversation-1",
                true
        ));

        assertThat(stream.retrievalMode()).isEqualTo(SearchMode.KEYWORD);
        assertThat(stream.sources()).hasSize(1);
        assertThat(stream.answer().collectList().block()).containsExactly("答案片段");
        assertThat(fixture.knowledgeStore.seenModes).containsExactly(SearchMode.HYBRID, SearchMode.KEYWORD);
    }

    @Test
    void pureModelChatUsesMemoryAdvisorWithoutSearchingKnowledgeBase() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("纯对话答案"));

        AgentChatStream stream = fixture.agent.stream(new AgentRequest(
                "request-2",
                "不用知识库也能回答吗？",
                5,
                SearchMode.HYBRID,
                "conversation-2",
                false
        ));

        assertThat(stream.retrievalMode()).isNull();
        assertThat(stream.sources()).isEmpty();
        assertThat(stream.answer().collectList().block()).containsExactly("纯对话答案");
        assertThat(fixture.knowledgeStore.seenModes).isEmpty();
        assertThat(fixture.runtime.lastAdvisors).hasSize(1);

        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-2");
        assertThat(messages)
                .extracting(ChatMessage::role)
                .containsExactly(ChatMessageRole.USER, ChatMessageRole.ASSISTANT);
        assertThat(messages.get(1).status()).isEqualTo(ChatMessageStatus.DONE);
        assertThat(messages.get(1).content()).isEqualTo("纯对话答案");
    }

    @Test
    void ragChatPassesRetrievalAdvisorAndPersistsSources() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("可以使用 Launch4j。"));

        AgentChatStream stream = fixture.agent.stream(new AgentRequest(
                "request-3",
                "如何打包？",
                3,
                SearchMode.KEYWORD,
                "conversation-3",
                true
        ));

        assertThat(stream.sources()).singleElement()
                .satisfies(source -> assertThat(source.content()).contains("Launch4j"));
        assertThat(stream.answer().collectList().block()).containsExactly("可以使用 Launch4j。");
        assertThat(fixture.runtime.lastAdvisors)
                .anySatisfy(advisor -> assertThat(advisor.getClass().getName()).contains("RetrievalAugmentationAdvisor"));
        assertThat(fixture.runtime.lastAdvisorParams)
                .containsEntry(ChatMemory.CONVERSATION_ID, "conversation-3")
                .containsEntry(CogninoteMemoryAdvisor.MAX_MESSAGE_SEQUENCE, 0);

        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-3");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).retrievalMode()).isEqualTo(SearchMode.KEYWORD);
        assertThat(fixture.chatSessionService.getSession("conversation-3").messages().get(1).sources())
                .singleElement()
                .satisfies(source -> assertThat(source.fileName()).isEqualTo("packaging.md"));
    }

    @Test
    void documentRetrieverOmitsNullMetadataValues() {
        CogninoteDocumentRetriever retriever = new CogninoteDocumentRetriever(
                new KnowledgeContextProvider(
                        new FakeKnowledgeStore(false, hitWithoutOptionalMetadata("chunk-null")),
                        new FakeDocumentRepository()
                ),
                "如何打包？",
                SearchMode.KEYWORD,
                3
        );

        List<Document> documents = retriever.retrieve(new Query("ignored by cogninote retriever"));

        assertThat(documents).singleElement().satisfies(document -> {
            assertThat(document.getMetadata()).doesNotContainKeys("heading", "pageNumber");
            assertThat(document.getMetadata().values()).doesNotContainNull();
        });
    }

    @Test
    void explicitCancelPersistsPartialAssistantMessageAsStopped() {
        AgentFixture fixture = new AgentFixture(
                new FakeKnowledgeStore(false),
                Flux.concat(Flux.just("半截"), Flux.never())
        );

        AgentChatStream stream = fixture.agent.stream(new AgentRequest(
                "request-4",
                "请长篇回答",
                3,
                SearchMode.KEYWORD,
                "conversation-4",
                true
        ));
        List<String> received = new ArrayList<>();

        Disposable subscription = stream.answer().subscribe(received::add);
        stream.onCancel().run();
        subscription.dispose();

        assertThat(received).containsExactly("半截");
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-4");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).status()).isEqualTo(ChatMessageStatus.STOPPED);
        assertThat(messages.get(1).content()).isEqualTo("半截");
    }

    private static ChatPromptProperties defaultPromptProperties() {
        return new ChatPromptProperties(
                new ChatPromptProperties.Rag(
                        """
                                你是 CogniNote Agent 的本地知识库问答助手。
                                请使用清晰的 Markdown 回答。
                                开启知识库时必须用 [1]、[2] 标注引用来源。
                                """,
                        """
                                用户问题：
                                {question}

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

    private static SearchHitResponse hitWithoutOptionalMetadata(String chunkId) {
        return new SearchHitResponse(
                chunkId,
                "doc-1",
                "packaging.md",
                "D:/notes/packaging.md",
                null,
                null,
                "CogniNote 使用 Launch4j 生成 Windows EXE。",
                1.0,
                1.0,
                null
        );
    }

    private static final class AgentFixture {
        private final FakeKnowledgeStore knowledgeStore;
        private final RecordingAiRuntime runtime;
        private final ChatSessionRepository chatSessionRepository;
        private final ChatSessionService chatSessionService;
        private final CogninoteChatAgent agent;

        private AgentFixture(FakeKnowledgeStore knowledgeStore, Flux<String> answer) {
            this.knowledgeStore = knowledgeStore;
            this.runtime = new RecordingAiRuntime(answer);
            SqlSession sqlSession = sqliteSqlSession();
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            ModelConfigRepository modelConfigRepository = new ModelConfigRepository(
                    sqlSession.getMapper(ModelConfigMapper.class)
            );
            modelConfigRepository.save(activeChatConfig());
            this.chatSessionRepository = new ChatSessionRepository(sqlSession.getMapper(ChatSessionMapper.class));
            ChatMemoryProperties memoryProperties = new ChatMemoryProperties(6000, 8, 40);
            TokenEstimator tokenEstimator = new TokenEstimator();
            this.chatSessionService = new ChatSessionService(
                    chatSessionRepository,
                    new RagSourcesJsonCodec(new ObjectMapper()),
                    tokenEstimator,
                    memoryProperties
            );
            this.agent = new CogninoteChatAgent(
                    new ModelConfigService(modelConfigRepository),
                    new FakeAiRuntimeFactory(runtime),
                    new KnowledgeContextProvider(knowledgeStore, new FakeDocumentRepository()),
                    new PromptAssembler(defaultPromptProperties()),
                    chatSessionService,
                    new CogninoteMemoryAdvisor(new ConversationMemorySnapshotService(
                            chatSessionRepository,
                            memoryProperties
                    ))
            );
        }

        private static SqlSession sqliteSqlSession() {
            try {
                SQLiteDataSource dataSource = new SQLiteDataSource();
                dataSource.setUrl("jdbc:sqlite::memory:");
                SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
                factoryBean.setDataSource(dataSource);
                factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:/mappers/*.xml"));
                SqlSessionFactory factory = factoryBean.getObject();
                if (factory == null) {
                    throw new IllegalStateException("Failed to create test MyBatis SqlSessionFactory");
                }
                return factory.openSession(true);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to create in-memory SQLite MyBatis session", ex);
            }
        }

        private static ModelConfig activeChatConfig() {
            long now = System.currentTimeMillis();
            return new ModelConfig(
                    ModelConfigDefaults.ACTIVE_CHAT_CONFIG_ID,
                    ModelConfigRole.CHAT,
                    ModelConfigDefaults.PROVIDER,
                    ModelConfigDefaults.CHAT_DISPLAY_NAME,
                    ModelConfigDefaults.BASE_URL,
                    "sk-test",
                    ModelConfigDefaults.CHAT_MODEL,
                    null,
                    ModelConfigDefaults.TEMPERATURE,
                    ModelConfigDefaults.TOP_K,
                    true,
                    now,
                    now
            );
        }
    }

    private static final class FakeKnowledgeStore implements KnowledgeStore {
        private final boolean failHybrid;
        private final SearchHitResponse hit;
        private final List<SearchMode> seenModes = new ArrayList<>();

        private FakeKnowledgeStore(boolean failHybrid) {
            this(failHybrid, hit("chunk-1"));
        }

        private FakeKnowledgeStore(boolean failHybrid, SearchHitResponse hit) {
            this.failHybrid = failHybrid;
            this.hit = hit;
        }

        @Override
        public void indexDocument(IndexedDocument document) {
        }

        @Override
        public void deleteByDocumentId(String documentId) {
        }

        @Override
        public RebuildIndexResponse rebuildByDocumentIds(List<IndexedDocument> documents) {
            return null;
        }

        @Override
        public SearchResponse search(SearchRequest request) {
            seenModes.add(request.modeOrDefault());
            if (failHybrid && request.modeOrDefault() == SearchMode.HYBRID) {
                throw new EmbeddingUnavailableException("hybrid search is unavailable");
            }
            return new SearchResponse(request.query(), request.modeOrDefault(), List.of(hit));
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

    private static final class FakeAiRuntimeFactory implements AiRuntimeFactory {
        private final RecordingAiRuntime runtime;

        private FakeAiRuntimeFactory(RecordingAiRuntime runtime) {
            this.runtime = runtime;
        }

        @Override
        public AiChatRuntime chatRuntime(ModelConfig config) {
            return runtime;
        }

        @Override
        public AiEmbeddingRuntime embeddingRuntime(ModelConfig config) {
            throw new UnsupportedOperationException("Embedding runtime is not used in this test");
        }
    }

    private static final class RecordingAiRuntime implements AiChatRuntime {
        private final Flux<String> stream;
        private String lastSystemPrompt;
        private String lastUserMessage;
        private List<Advisor> lastAdvisors = List.of();
        private Map<String, Object> lastAdvisorParams = Map.of();

        private RecordingAiRuntime(Flux<String> stream) {
            this.stream = stream;
        }

        @Override
        public Flux<String> stream(Prompt prompt) {
            return stream;
        }

        @Override
        public Flux<String> stream(
                String systemPrompt,
                String userMessage,
                List<Advisor> advisors,
                Map<String, Object> advisorParams
        ) {
            this.lastSystemPrompt = systemPrompt;
            this.lastUserMessage = userMessage;
            this.lastAdvisors = advisors == null ? List.of() : List.copyOf(advisors);
            this.lastAdvisorParams = advisorParams == null ? Map.of() : Map.copyOf(advisorParams);
            return stream;
        }

        @Override
        public void testConnection(Prompt prompt) {
        }
    }

    private static final class FakeDocumentRepository extends DocumentRepository {

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
