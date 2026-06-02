package com.itqianchen.agentdesign.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.document.DocumentIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-vector-search",
        "app.storage.database-path=target/test-cogninote-vector-search/cogninote.db",
        "server.address=127.0.0.1"
})
class SearchVectorIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearState() {
        jdbcTemplate.update("DELETE FROM chunks");
        jdbcTemplate.update("DELETE FROM documents");
        knowledgeStore.rebuildAll();
    }

    @Test
    void vectorAndHybridSearchUseConfiguredEmbeddingGateway() throws Exception {
        Files.writeString(tempDir.resolve("alpha.txt"), "alpha memory vector payload");
        Files.writeString(tempDir.resolve("beta.txt"), "beta unrelated payload");
        ingestionService.ingestFolder(tempDir.toString(), true);

        mockMvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingConfigured").value(true));

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "alpha question",
                                  "mode": "VECTOR",
                                  "topK": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].fileName").value("alpha.txt"));

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "alpha payload",
                                  "mode": "HYBRID",
                                  "topK": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].fileName").value("alpha.txt"));
    }

    @TestConfiguration
    static class FakeEmbeddingConfiguration {

        @Bean
        @Primary
        EmbeddingGateway fakeEmbeddingGateway() {
            return new EmbeddingGateway() {
                @Override
                public boolean isAvailable() {
                    return true;
                }

                @Override
                public int dimensions() {
                    return 4;
                }

                @Override
                public List<float[]> embedBatch(List<String> texts) {
                    return texts.stream()
                            .map(this::embed)
                            .toList();
                }

                private float[] embed(String text) {
                    String lowerCaseText = text.toLowerCase();
                    if (lowerCaseText.contains("alpha")) {
                        return new float[]{1.0f, 0.0f, 0.0f, 0.0f};
                    }
                    if (lowerCaseText.contains("beta")) {
                        return new float[]{0.0f, 1.0f, 0.0f, 0.0f};
                    }
                    return new float[]{0.0f, 0.0f, 1.0f, 0.0f};
                }
            };
        }
    }
}
