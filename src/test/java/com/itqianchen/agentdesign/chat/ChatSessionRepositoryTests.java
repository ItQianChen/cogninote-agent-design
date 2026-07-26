package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.enums.agent.AgentType;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.entity.chat.ChatSession;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.mapper.test.TestDatabaseMapper;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 覆盖聊天会话仓储的核心边界。
 *
 * <p>重点保护 SSE 预分配 conversationId 和会话物理删除消息两类行为。</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "server.address=127.0.0.1"
})
class ChatSessionRepositoryTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @Autowired
    private TestDatabaseMapper testDatabaseMapper;

    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearChat();
    }

    @Test
    void createWithSameIdReturnsExistingSessionInsteadOfFailing() {
        long now = System.currentTimeMillis();
        ChatSession first = chatSessionRepository.create(
                "conversation-race",
                "First",
                true,
                SearchMode.HYBRID,
                8,
                now
        );
        ChatSession second = chatSessionRepository.create(
                "conversation-race",
                "Second",
                false,
                SearchMode.KEYWORD,
                3,
                now + 1
        );

        assertThat(second).isEqualTo(first);
        assertThat(chatSessionRepository.findActiveSessions())
                .singleElement()
                .satisfies(session -> {
                    assertThat(session.id()).isEqualTo("conversation-race");
                    assertThat(session.title()).isEqualTo("First");
                    assertThat(session.useKnowledgeBase()).isTrue();
                    assertThat(session.retrievalMode()).isEqualTo(SearchMode.HYBRID);
                    assertThat(session.topK()).isEqualTo(8);
                });
    }

    @Test
    void deleteSessionPhysicallyRemovesSessionAndMessages() {
        long now = System.currentTimeMillis();
        ChatSession session = chatSessionRepository.create(
                "conversation-delete",
                "Delete me",
                true,
                SearchMode.HYBRID,
                8,
                now
        );
        chatSessionRepository.appendMessage(
                session.id(),
                ChatMessageRole.USER,
                "hello",
                ChatMessageStatus.DONE,
                "request-delete",
                AgentType.GENERAL_CHAT,
                null,
                null,
                null,
                1,
                now + 1
        );

        assertThat(testDatabaseMapper.countChatSessionsById(session.id())).isEqualTo(1);
        assertThat(testDatabaseMapper.countChatMessagesByConversationId(session.id())).isEqualTo(1);

        assertThat(chatSessionRepository.deleteSession(session.id())).isTrue();

        assertThat(chatSessionRepository.findById(session.id())).isEmpty();
        assertThat(chatSessionRepository.findMessages(session.id())).isEmpty();
        assertThat(testDatabaseMapper.countChatSessionsById(session.id())).isZero();
        assertThat(testDatabaseMapper.countChatMessagesByConversationId(session.id())).isZero();
    }
}
