package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionMapper;
import com.itqianchen.agentdesign.mapper.model.ModelConfigMapper;
import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import com.itqianchen.agentdesign.metadata.DatabaseSchemaInitializer;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.service.chat.ChatContextUsageService;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshot;
import com.itqianchen.agentdesign.service.chat.ConversationMemorySnapshotService;
import com.itqianchen.agentdesign.service.chat.TokenEstimator;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteDataSource;

class ChatContextUsageServiceTests {

    @Test
    void summarizeAfterMessagesDoesNotTriggerAtFortyWhenTokensFitWindow() {
        Fixture fixture = new Fixture(ModelConfigDefaults.CONTEXT_WINDOW_TOKENS);
        ChatSession session = fixture.createSession("conversation-40");
        fixture.appendMessages(session.id(), 41, "短消息");

        boolean shouldSummarize = fixture.contextUsageService.shouldSummarize(
                fixture.repository.findById(session.id()).orElseThrow(),
                fixture.repository.findMessages(session.id())
        );

        assertThat(shouldSummarize).isFalse();
    }

    @Test
    void snapshotKeepsMinimumRecentMessagesEvenWhenOverBudget() {
        Fixture fixture = new Fixture(ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS);
        ChatSession session = fixture.createSession("conversation-budget");
        fixture.appendMessages(session.id(), 12, "这是一条会撑爆上下文预算的长消息。".repeat(240));

        ConversationMemorySnapshot snapshot = fixture.snapshotService.snapshot(session.id());

        assertThat(snapshot.recentMessages()).hasSize(8);
        assertThat(snapshot.recentMessages().getFirst().content()).contains("长消息");
        assertThat(snapshot.contextWindowTokens()).isEqualTo(ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS);
    }

    @Test
    void contextUsageRecountsSummaryAndRecentMessagesAfterCompression() {
        Fixture fixture = new Fixture(ModelConfigDefaults.CONTEXT_WINDOW_TOKENS);
        ChatSession session = fixture.createSession("conversation-summary");
        fixture.appendMessages(session.id(), 12, "摘要重算测试");
        fixture.repository.updateSummary(
                session.id(),
                "这是较早消息的滚动摘要，用于测试压缩后的上下文占用重算。",
                4,
                System.currentTimeMillis()
        );

        ChatContextUsageResponse usage = fixture.contextUsageService.usage(session.id());

        assertThat(usage.compressed()).isTrue();
        assertThat(usage.summaryTokens()).isPositive();
        assertThat(usage.recentMessageCount()).isEqualTo(8);
        assertThat(usage.totalMessageCount()).isEqualTo(12);
        assertThat(usage.usedTokens()).isEqualTo(usage.summaryTokens() + usage.recentMessageTokens());
    }

    private static final class Fixture {
        private final ChatSessionRepository repository;
        private final ConversationMemorySnapshotService snapshotService;
        private final ChatContextUsageService contextUsageService;

        private Fixture(int contextWindowTokens) {
            SqlSession sqlSession = sqliteSqlSession();
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            ModelConfigRepository modelConfigRepository = new ModelConfigRepository(
                    sqlSession.getMapper(ModelConfigMapper.class)
            );
            modelConfigRepository.save(chatConfig(contextWindowTokens));
            ModelConfigService modelConfigService = new ModelConfigService(modelConfigRepository);
            ChatMemoryProperties memoryProperties = new ChatMemoryProperties(6000, 8, 200);
            TokenEstimator tokenEstimator = new TokenEstimator();
            this.repository = new ChatSessionRepository(sqlSession.getMapper(ChatSessionMapper.class));
            this.snapshotService = new ConversationMemorySnapshotService(
                    repository,
                    memoryProperties,
                    tokenEstimator,
                    modelConfigService
            );
            this.contextUsageService = new ChatContextUsageService(
                    repository,
                    snapshotService,
                    modelConfigService,
                    memoryProperties
            );
        }

        private ChatSession createSession(String id) {
            return repository.create(id, "测试会话", false, SearchMode.HYBRID, 8, System.currentTimeMillis());
        }

        private void appendMessages(String conversationId, int count, String content) {
            for (int index = 0; index < count; index += 1) {
                appendMessage(conversationId, content + " #" + index);
            }
        }

        private ChatMessage appendMessage(String conversationId, String content) {
            return repository.appendMessage(
                    conversationId,
                    ChatMessageRole.USER,
                    content,
                    ChatMessageStatus.DONE,
                    "request-" + System.nanoTime(),
                    AgentType.GENERAL_CHAT,
                    null,
                    null,
                    0,
                    System.currentTimeMillis()
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

        private static ModelConfig chatConfig(int contextWindowTokens) {
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
                    contextWindowTokens,
                    true,
                    now,
                    now
            );
        }
    }
}
