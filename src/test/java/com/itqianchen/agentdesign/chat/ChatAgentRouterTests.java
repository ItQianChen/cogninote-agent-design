package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.ai.AiEmbeddingRuntime;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import com.itqianchen.agentdesign.domain.chat.QueryContextualizerMode;
import com.itqianchen.agentdesign.domain.chat.QueryContextualizerProperties;
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
import com.itqianchen.agentdesign.mapper.settings.AppSettingMapper;
import com.itqianchen.agentdesign.metadata.DatabaseSchemaInitializer;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import com.itqianchen.agentdesign.service.agent.ChatAgentRouter;
import com.itqianchen.agentdesign.service.agent.GeneralChatAgent;
import com.itqianchen.agentdesign.service.agent.CogninoteDocumentRetriever;
import com.itqianchen.agentdesign.service.agent.KnowledgeContextProvider;
import com.itqianchen.agentdesign.service.agent.KnowledgeBaseChatAgent;
import com.itqianchen.agentdesign.service.agent.PromptAssembler;
import com.itqianchen.agentdesign.service.agent.QueryContextualizerAgent;
import com.itqianchen.agentdesign.service.agent.QueryContextualizerTriggerDecider;
import com.itqianchen.agentdesign.service.chat.ChatContextUsageService;
import com.itqianchen.agentdesign.service.chat.ChatSettingsService;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshotService;
import com.itqianchen.agentdesign.dto.chat.ChatSettingsRequest;
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

/**
 * Chat 智能体 路由器 测试 承担 聊天会话 模块的主要职责。
 * <p>注释说明维护边界，不改变现有运行逻辑。</p>
 */
class ChatAgentRouterTests {

    /**
     * 执行 聊天会话 中的 prompt Assembler No Longer Requires Manual Context Placeholder 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @Test
    void promptAssemblerNoLongerRequiresManualContextPlaceholder() {
        PromptAssembler promptAssembler = new PromptAssembler(defaultPromptProperties());

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(promptAssembler.systemPrompt(AgentType.KNOWLEDGE_BASE)).contains("Markdown");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(promptAssembler.userPrompt(AgentType.KNOWLEDGE_BASE, "如何打包？"))
                .contains("如何打包？")
                .doesNotContain("{context}");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(promptAssembler.systemPrompt(AgentType.GENERAL_CHAT))
                .contains("普通对话助手")
                .doesNotContain("当前知识库中没有足够依据");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(promptAssembler.emptyContextPrompt()).contains("没有检索到");
    }

    /**
     * 执行 聊天会话 中的 hybrid Falls Back To Keyword When Embedding Is Unavailable 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.retrievalMode()).isEqualTo(SearchMode.KEYWORD);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.sources()).hasSize(1);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.answer().collectList().block()).containsExactly("答案片段");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.knowledgeStore.seenModes).containsExactly(SearchMode.HYBRID, SearchMode.KEYWORD);
    }

    /**
     * 执行 聊天会话 中的 pure Model Chat Uses Memory Advisor Without Searching Knowledge Base 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.retrievalMode()).isNull();
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.sources()).isEmpty();
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.answer().collectList().block()).containsExactly("纯对话答案");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.knowledgeStore.seenModes).isEmpty();
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastAdvisors).hasSize(1);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastSystemPrompt)
                .contains("普通对话助手")
                .doesNotContain("知识库中没有足够依据");

        // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-2");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages)
                .extracting(ChatMessage::role)
                .containsExactly(ChatMessageRole.USER, ChatMessageRole.ASSISTANT);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).status()).isEqualTo(ChatMessageStatus.DONE);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).content()).isEqualTo("纯对话答案");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(0).agentType()).isEqualTo(AgentType.GENERAL_CHAT);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).agentType()).isEqualTo(AgentType.GENERAL_CHAT);
    }

    /**
     * 执行 聊天会话 中的 rag Chat Passes Retrieval Advisor And Persists Sources 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.sources()).singleElement()
                .satisfies(source -> assertThat(source.content()).contains("Launch4j"));
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.answer().collectList().block()).containsExactly("可以使用 Launch4j。");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastAdvisors)
                .anySatisfy(advisor -> assertThat(advisor.getClass().getName()).contains("RetrievalAugmentationAdvisor"));
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastAdvisorParams)
                .containsEntry(ChatMemory.CONVERSATION_ID, "conversation-3")
                .containsEntry(CogninoteMemoryAdvisor.MAX_MESSAGE_SEQUENCE, 0)
                .containsEntry(CogninoteMemoryAdvisor.AGENT_TYPE, AgentType.KNOWLEDGE_BASE);

        // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-3");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages).hasSize(2);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).retrievalMode()).isEqualTo(SearchMode.KEYWORD);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).agentType()).isEqualTo(AgentType.KNOWLEDGE_BASE);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.chatSessionService.getSession("conversation-3").messages().get(1).sources())
                .singleElement()
                .satisfies(source -> assertThat(source.fileName()).isEqualTo("packaging.md"));
    }

    /**
     * 执行 聊天会话 中的 switching From Knowledge Base To Pure Chat Uses General Prompt And Keeps History As Reference Only 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @Test
    void switchingFromKnowledgeBaseToPureChatUsesGeneralPromptAndKeepsHistoryAsReferenceOnly() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("知识库依据不足"));
        fixture.agent.stream(new AgentRequest(
                "request-5",
                "知识库里有 Java 吗？",
                3,
                SearchMode.KEYWORD,
                "conversation-5",
                true
        )).answer().collectList().block();

        fixture.runtime.stream = Flux.just("Java 是一种通用编程语言。");
        AgentChatStream pureStream = fixture.agent.stream(new AgentRequest(
                "request-6",
                "Java 是什么？",
                3,
                SearchMode.KEYWORD,
                "conversation-5",
                false
        ));

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(pureStream.retrievalMode()).isNull();
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(pureStream.answer().collectList().block()).containsExactly("Java 是一种通用编程语言。");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastSystemPrompt)
                .contains("普通对话助手")
                .doesNotContain("当前知识库中没有足够依据");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastAdvisorParams)
                .containsEntry(CogninoteMemoryAdvisor.AGENT_TYPE, AgentType.GENERAL_CHAT);

        // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-5");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages).hasSize(4);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).agentType()).isEqualTo(AgentType.KNOWLEDGE_BASE);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(3).agentType()).isEqualTo(AgentType.GENERAL_CHAT);
    }

    /**
     * 执行 聊天会话 中的 document Retriever Omits Null Metadata Values 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @Test
    void documentRetrieverOmitsNullMetadataValues() {
        CogninoteDocumentRetriever retriever = new CogninoteDocumentRetriever(
                new KnowledgeContextProvider(
                        new FakeKnowledgeStore(false, hitWithoutOptionalMetadata("chunk-null")),
                        new FakeDocumentRepository()
                ),
                "如何打包？",
                "如何打包？",
                SearchMode.KEYWORD,
                3
        );

        List<Document> documents = retriever.retrieve(new Query("ignored by cogninote retriever"));

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(documents).singleElement().satisfies(document -> {
            /**
             * 执行 聊天会话 中的 assert That 步骤。
             * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
             */
            assertThat(document.getMetadata()).doesNotContainKeys("heading", "pageNumber");
            /**
             * 执行 聊天会话 中的 assert That 步骤。
             * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
             */
            assertThat(document.getMetadata().values()).doesNotContainNull();
        });
    }

    /**
     * 执行 聊天会话 中的 explicit Cancel Persists Partial Assistant Message As Stopped 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(received).containsExactly("半截");
        // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-4");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages).hasSize(2);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).status()).isEqualTo(ChatMessageStatus.STOPPED);
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages.get(1).content()).isEqualTo("半截");
    }

    /**
     * 执行 聊天会话 中的 knowledge Base Contextualizes Follow Up Question For Retrieval Only 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @Test
    void knowledgeBaseContextualizesFollowUpQuestionForRetrievalOnly() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("红黑树说明"));
        fixture.agent.stream(new AgentRequest(
                "request-rbtree-1",
                "红黑树是什么？在 Java 中哪里用到了这个结构？",
                3,
                SearchMode.KEYWORD,
                "conversation-rbtree",
                true
        )).answer().collectList().block();

        fixture.runtime.stream = Flux.just("下面给出 Java 中 TreeMap 的红黑树使用示例。");
        fixture.runtime.contextualizerResponse = """
                {"shouldRewrite":true,"rewrittenQuery":"红黑树是什么？在 Java 中哪里用到了这个结构？ 给出代码示例","reason":"当前问题是上一轮红黑树主题的省略式追问","confidence":0.93}
                """;
        AgentChatStream stream = fixture.agent.stream(new AgentRequest(
                "request-rbtree-2",
                "给出代码示例",
                3,
                SearchMode.KEYWORD,
                "conversation-rbtree",
                true
        ));

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(stream.answer().collectList().block())
                .containsExactly("下面给出 Java 中 TreeMap 的红黑树使用示例。");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.knowledgeStore.seenQueries.getLast())
                .contains("红黑树")
                .contains("Java")
                .contains("代码示例");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastCallTextUserMessage)
                .contains("红黑树是什么？在 Java 中哪里用到了这个结构？")
                .contains("给出代码示例");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.runtime.lastUserMessage).contains("给出代码示例");
        // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
        List<ChatMessage> messages = fixture.chatSessionRepository.findMessages("conversation-rbtree");
        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(messages)
                .extracting(ChatMessage::content)
                .contains("给出代码示例")
                .doesNotContain("红黑树是什么？在 Java 中哪里用到了这个结构？ 给出代码示例");
    }

    /**
     * 验证 AUTO 模式不会为完整独立问题额外调用补全 Agent。
     * <p>完整问题直接检索，避免在 128K 以上上下文时代为普通知识库问答增加固定延迟。</p>
     */
    @Test
    void autoModeSkipsContextualizerForStandaloneQuestion() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("红黑树说明"));
        fixture.agent.stream(new AgentRequest(
                "request-auto-standalone-1",
                "红黑树是什么？",
                3,
                SearchMode.KEYWORD,
                "conversation-auto-standalone",
                true
        )).answer().collectList().block();

        int callsBeforeStandaloneQuestion = fixture.runtime.callTextCalls;
        fixture.runtime.stream = Flux.just("HashMap 扩容说明");
        fixture.agent.stream(new AgentRequest(
                "request-auto-standalone-2",
                "HashMap 是怎么扩容的？",
                3,
                SearchMode.KEYWORD,
                "conversation-auto-standalone",
                true
        )).answer().collectList().block();

        assertThat(fixture.runtime.callTextCalls).isEqualTo(callsBeforeStandaloneQuestion);
        assertThat(fixture.knowledgeStore.seenQueries.getLast()).isEqualTo("HashMap 是怎么扩容的？");
    }

    /**
     * 验证 OFF 模式完全关闭追问补全。
     * <p>即使用户输入明显省略式追问，也必须保持检索 query 等于用户原问题。</p>
     */
    @Test
    void offModeUsesOriginalQuestionWithoutCallingContextualizer() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("红黑树说明"));
        fixture.setQueryContextualizerMode(QueryContextualizerMode.OFF);
        fixture.runtime.contextualizerResponse = """
                {"shouldRewrite":true,"rewrittenQuery":"红黑树 给出代码示例","reason":"test_should_not_call","confidence":1.0}
                """;
        fixture.agent.stream(new AgentRequest(
                "request-off-1",
                "红黑树是什么？",
                3,
                SearchMode.KEYWORD,
                "conversation-off",
                true
        )).answer().collectList().block();

        fixture.runtime.stream = Flux.just("代码示例");
        fixture.agent.stream(new AgentRequest(
                "request-off-2",
                "给出代码示例",
                3,
                SearchMode.KEYWORD,
                "conversation-off",
                true
        )).answer().collectList().block();

        assertThat(fixture.runtime.callTextCalls).isZero();
        assertThat(fixture.knowledgeStore.seenQueries.getLast()).isEqualTo("给出代码示例");
    }

    /**
     * 执行 聊天会话 中的 contextualizer Falls Back To Original Question When Model Returns Invalid Json 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    @Test
    void contextualizerFallsBackToOriginalQuestionWhenModelReturnsInvalidJson() {
        AgentFixture fixture = new AgentFixture(new FakeKnowledgeStore(false), Flux.just("仍然可以回答。"));
        fixture.setQueryContextualizerMode(QueryContextualizerMode.ALWAYS);
        fixture.runtime.contextualizerResponse = "不是 JSON";

        fixture.agent.stream(new AgentRequest(
                "request-invalid-json",
                "HashMap 是怎么扩容的？",
                3,
                SearchMode.KEYWORD,
                "conversation-invalid-json",
                true
        )).answer().collectList().block();

        /**
         * 执行 聊天会话 中的 assert That 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        assertThat(fixture.knowledgeStore.seenQueries.getLast()).isEqualTo("HashMap 是怎么扩容的？");
    }

    /**
     * 执行 聊天会话 中的 default Prompt 配置属性 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static ChatPromptProperties defaultPromptProperties() {
        return new ChatPromptProperties(
                new ChatPromptProperties.PromptTemplate(
                        """
                                你是 CogniNote Agent 的普通对话助手。
                                当前不使用知识库，也不需要引用来源。
                                """,
                        """
                                用户问题：
                                {question}
                                """
                ),
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
                new ChatPromptProperties.QueryContextualizer(
                        """
                                你是检索问题补全判断器。只返回 JSON。
                                """,
                        """
                                历史消息：
                                {history}

                                当前问题：
                                {question}
                                """
                ),
                new ChatPromptProperties.ConnectionTest("请用一句话回答：CogniNote 连接测试是否可用？")
        );
    }

    /**
     * 执行 聊天会话 中的 hit 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

    /**
     * 执行 聊天会话 中的 hit Without Optional Metadata 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
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

    /**
     * 智能体 Fixture 承担 聊天会话 模块的主要职责。
     * <p>注释说明维护边界，不改变现有运行逻辑。</p>
     */
    private static final class AgentFixture {
        private final FakeKnowledgeStore knowledgeStore;
        private final RecordingAiRuntime runtime;
        private final ChatSessionRepository chatSessionRepository;
        private final ChatSessionService chatSessionService;
        private final ChatSettingsService chatSettingsService;
        private final ChatAgentRouter agent;

        /**
         * 执行 聊天会话 中的 智能体 Fixture 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private AgentFixture(FakeKnowledgeStore knowledgeStore, Flux<String> answer) {
            this.knowledgeStore = knowledgeStore;
            this.runtime = new RecordingAiRuntime(answer);
            SqlSession sqlSession = sqliteSqlSession();
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            ModelConfigRepository modelConfigRepository = new ModelConfigRepository(
                    sqlSession.getMapper(ModelConfigMapper.class)
            );
            QueryContextualizerProperties queryContextualizerProperties = new QueryContextualizerProperties(
                    true,
                    null,
                    6,
                    800
            );
            this.chatSettingsService = new ChatSettingsService(
                    new AppSettingRepository(sqlSession.getMapper(AppSettingMapper.class)),
                    queryContextualizerProperties
            );
            // 写入会影响本地 SQLite 状态，调用顺序需要和会话状态机保持一致。
            modelConfigRepository.save(activeChatConfig());
            ModelConfigService modelConfigService = new ModelConfigService(modelConfigRepository);
            this.chatSessionRepository = new ChatSessionRepository(sqlSession.getMapper(ChatSessionMapper.class));
            ChatMemoryProperties memoryProperties = new ChatMemoryProperties(6000, 8, 200);
            TokenEstimator tokenEstimator = new TokenEstimator();
            ConversationMemorySnapshotService memorySnapshotService = new ConversationMemorySnapshotService(
                    chatSessionRepository,
                    memoryProperties,
                    tokenEstimator,
                    modelConfigService
            );
            ChatContextUsageService contextUsageService = new ChatContextUsageService(
                    chatSessionRepository,
                    memorySnapshotService,
                    modelConfigService,
                    memoryProperties
            );
            this.chatSessionService = new ChatSessionService(
                    chatSessionRepository,
                    new RagSourcesJsonCodec(new ObjectMapper()),
                    tokenEstimator,
                    memoryProperties,
                    contextUsageService,
                    modelConfigService
            );
            FakeAiRuntimeFactory runtimeFactory = new FakeAiRuntimeFactory(runtime);
            PromptAssembler promptAssembler = new PromptAssembler(defaultPromptProperties());
            CogninoteMemoryAdvisor memoryAdvisor = new CogninoteMemoryAdvisor(memorySnapshotService);
            QueryContextualizerAgent queryContextualizerAgent = new QueryContextualizerAgent(
                    runtimeFactory,
                    defaultPromptProperties(),
                    queryContextualizerProperties,
                    chatSettingsService,
                    memorySnapshotService,
                    new QueryContextualizerTriggerDecider(),
                    new ObjectMapper()
            );
            this.agent = new ChatAgentRouter(List.of(
                    new GeneralChatAgent(
                            modelConfigService,
                            runtimeFactory,
                            promptAssembler,
                            chatSessionService,
                            memoryAdvisor
                    ),
                    new KnowledgeBaseChatAgent(
                            modelConfigService,
                            runtimeFactory,
                            new KnowledgeContextProvider(knowledgeStore, new FakeDocumentRepository()),
                            promptAssembler,
                            chatSessionService,
                            memoryAdvisor,
                            queryContextualizerAgent
                    )
            ));
        }

        /**
         * 保存测试用追问补全模式。
         * <p>通过真实 ChatSettingsService 写入 SQLite，覆盖前端 API 使用的同一条配置路径。</p>
         */
        private void setQueryContextualizerMode(QueryContextualizerMode mode) {
            chatSettingsService.update(new ChatSettingsRequest(mode));
        }

        /**
         * 执行 聊天会话 中的 sqlite Sql Session 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
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

        /**
         * 执行 聊天会话 中的 active Chat 配置 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
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
                    ModelConfigDefaults.CONTEXT_WINDOW_TOKENS,
                    true,
                    now,
                    now
            );
        }
    }

    /**
     * Fake Knowledge 存储 是 聊天会话 的存储实现。
     * <p>对上层暴露领域接口，对下层封装具体索引或持久化细节。</p>
     */
    private static final class FakeKnowledgeStore implements KnowledgeStore {
        private final boolean failHybrid;
        private final SearchHitResponse hit;
        private final List<SearchMode> seenModes = new ArrayList<>();
        private final List<String> seenQueries = new ArrayList<>();

        /**
         * 执行 聊天会话 中的 Fake Knowledge 存储 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private FakeKnowledgeStore(boolean failHybrid) {
            this(failHybrid, hit("chunk-1"));
        }

        /**
         * 执行 聊天会话 中的 Fake Knowledge 存储 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private FakeKnowledgeStore(boolean failHybrid, SearchHitResponse hit) {
            this.failHybrid = failHybrid;
            this.hit = hit;
        }

        /**
         * 执行 聊天会话 中的 index Document 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public void indexDocument(IndexedDocument document) {
        }

        /**
         * 删除 delete By Document Id 对应的数据。
         * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
         */
        @Override
        public void deleteByDocumentId(String documentId) {
        }

        /**
         * 执行 聊天会话 中的 rebuild By Document Ids 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public RebuildIndexResponse rebuildByDocumentIds(List<IndexedDocument> documents) {
            return null;
        }

        /**
         * 执行 聊天会话 中的 search 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public SearchResponse search(SearchRequest request) {
            seenModes.add(request.modeOrDefault());
            seenQueries.add(request.query());
            if (failHybrid && request.modeOrDefault() == SearchMode.HYBRID) {
                throw new EmbeddingUnavailableException("hybrid search is unavailable");
            }
            return new SearchResponse(request.query(), request.modeOrDefault(), List.of(hit));
        }

        /**
         * 执行 聊天会话 中的 status 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public IndexStatusResponse status() {
            return null;
        }

        /**
         * 执行 聊天会话 中的 rebuild All 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public RebuildIndexResponse rebuildAll() {
            return null;
        }
    }

    /**
     * Fake Ai 运行时 工厂 负责创建 聊天会话 运行对象。
     * <p>提供商差异、客户端参数和缓存复用应收敛在这里。</p>
     */
    private static final class FakeAiRuntimeFactory implements AiRuntimeFactory {
        private final RecordingAiRuntime runtime;

        /**
         * 执行 聊天会话 中的 Fake Ai 运行时 工厂 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private FakeAiRuntimeFactory(RecordingAiRuntime runtime) {
            this.runtime = runtime;
        }

        /**
         * 执行 聊天会话 中的 chat 运行时 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public AiChatRuntime chatRuntime(ModelConfig config) {
            return runtime;
        }

        /**
         * 执行 聊天会话 中的 embedding 运行时 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        @Override
        public AiEmbeddingRuntime embeddingRuntime(ModelConfig config) {
            throw new UnsupportedOperationException("Embedding runtime is not used in this test");
        }
    }

    /**
     * Recording Ai 运行时 封装外部 聊天会话 调用。
     * <p>上层只依赖本地接口，不直接感知 Spring AI 或厂商 SDK 的细节。</p>
     */
    private static final class RecordingAiRuntime implements AiChatRuntime {
        private Flux<String> stream;
        private String contextualizerResponse = """
                {"shouldRewrite":false,"rewrittenQuery":"","reason":"test_keep_original","confidence":0.0}
                """;
        private String lastSystemPrompt;
        private String lastUserMessage;
        private String lastCallTextSystemPrompt;
        private String lastCallTextUserMessage;
        private int callTextCalls;
        private List<Advisor> lastAdvisors = List.of();
        private Map<String, Object> lastAdvisorParams = Map.of();

        /**
         * 执行 聊天会话 中的 Recording Ai 运行时 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private RecordingAiRuntime(Flux<String> stream) {
            this.stream = stream;
        }

        /**
         * 启动 stream 流式流程。
         * <p>方法串联请求准备、事件流返回和结束后的状态收尾。</p>
         */
        @Override
        public Flux<String> stream(Prompt prompt) {
            return stream;
        }

        /**
         * 启动 stream 流式流程。
         * <p>方法串联请求准备、事件流返回和结束后的状态收尾。</p>
         */
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

        /**
         * 执行一次同步 call Text 调用。
         * <p>外部模型响应会被转换为本地可处理的文本结果。</p>
         */
        @Override
        public String callText(String systemPrompt, String userMessage) {
            callTextCalls++;
            this.lastCallTextSystemPrompt = systemPrompt;
            this.lastCallTextUserMessage = userMessage;
            return contextualizerResponse;
        }

        /**
         * 测试 test Connection 是否可用。
         * <p>使用最小请求验证配置、网络和模型服务是否连通。</p>
         */
        @Override
        public void testConnection(Prompt prompt) {
        }
    }

    /**
     * Fake Document 仓储 是 聊天会话 的持久化边界。
     * <p>服务层通过该类型访问数据，避免直接依赖 MyBatis Mapper 细节。</p>
     */
    private static final class FakeDocumentRepository extends DocumentRepository {

        /**
         * 执行 聊天会话 中的 Fake Document 仓储 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private FakeDocumentRepository() {
            super(null);
        }

        /**
         * 读取 find Stored Chunks By Ids 对应的数据。
         * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
         */
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
