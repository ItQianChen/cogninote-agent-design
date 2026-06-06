package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-chat-session-repository",
        "app.storage.database-path=target/test-cogninote-chat-session-repository/cogninote.db",
        "server.address=127.0.0.1"
})
class ChatSessionRepositoryTests {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

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
}
