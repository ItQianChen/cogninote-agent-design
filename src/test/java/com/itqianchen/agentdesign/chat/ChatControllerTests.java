package com.itqianchen.agentdesign.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.domain.chat.LlmGateway;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.search.EmbeddingGateway;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import reactor.core.publisher.Flux;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-chat-controller",
        "app.storage.database-path=target/test-cogninote-chat-controller/cogninote.db",
        "server.address=127.0.0.1"
})
class ChatControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearState() {
        jdbcTemplate.update("DELETE FROM chunks");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("DELETE FROM model_configs");
        jdbcTemplate.update("DELETE FROM model_config");
    }

    @Test
    void chatStreamRequiresConfiguredModel() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"hello\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatStreamReturnsMetaDeltaAndDoneEvents() throws Exception {
        Files.writeString(tempDir.resolve("packaging.md"), "CogniNote uses Launch4j for Windows EXE packaging.");
        modelConfigService.save(new ModelConfigRequest(
                null,
                "DASHSCOPE",
                "DashScope",
                ModelConfigDefaults.BASE_URL,
                "sk-test",
                null,
                "qwen-plus",
                "text-embedding-v4",
                1024,
                0.7,
                8,
                null
        ));
        insertParsedDocument();
        knowledgeStore.rebuildAll();

        MvcResult start = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How does packaging work?",
                                  "mode": "KEYWORD",
                                  "topK": 3
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(start))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:meta")
                .contains("event:delta")
                .contains("event:done")
                .contains("packaging.md");
    }

    private void insertParsedDocument() {
        long now = System.currentTimeMillis();
        jdbcTemplate.update("""
                        INSERT INTO documents (
                            id, source_path, file_name, file_type, file_size, last_modified,
                            content_hash, status, indexed_at, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "doc-1",
                tempDir.resolve("packaging.md").toString(),
                "packaging.md",
                "MARKDOWN",
                10,
                now,
                "hash",
                "PARSED",
                now,
                now,
                now
        );
        jdbcTemplate.update("""
                        INSERT INTO chunks (
                            id, document_id, chunk_index, content, content_hash,
                            page_number, heading, token_count, created_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                "chunk-1",
                "doc-1",
                0,
                "CogniNote uses Launch4j for Windows EXE packaging.",
                "chunk-hash",
                null,
                "Packaging",
                12,
                now
        );
    }

    @TestConfiguration
    static class FakeLlmConfiguration {

        @Bean
        @Primary
        LlmGateway fakeLlmGateway() {
            return new LlmGateway() {
                @Override
                public Flux<String> stream(ModelConfig config, Prompt prompt) {
                    return Flux.just("可以使用 Launch4j 打包。");
                }

                @Override
                public void testConnection(ModelConfig config) {
                }
            };
        }

        @Bean
        @Primary
        EmbeddingGateway fakeEmbeddingGateway() {
            return new EmbeddingGateway() {
                @Override
                public boolean isAvailable() {
                    return false;
                }

                @Override
                public int dimensions() {
                    return 4;
                }

                @Override
                public java.util.List<float[]> embedBatch(java.util.List<String> texts) {
                    throw new UnsupportedOperationException("Embedding is intentionally disabled in chat controller tests");
                }
            };
        }
    }
}
