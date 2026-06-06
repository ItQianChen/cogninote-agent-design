package com.itqianchen.agentdesign.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-search",
        "app.storage.database-path=target/test-cogninote-search/cogninote.db",
        "server.address=127.0.0.1"
})
class SearchIndexIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearState() {
        databaseCleaner.clearDocuments();
        knowledgeStore.rebuildAll();
    }

    @Test
    void ingestMarkdownAutomaticallyIndexesKeywordSearch() throws Exception {
        Files.writeString(tempDir.resolve("packaging.md"), "# 打包方案\n\nCogniNote 使用 Launch4j 生成 Windows EXE。");

        ingestionService.ingestFolder(tempDir.toString(), true);

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "Launch4j",
                                  "mode": "KEYWORD",
                                  "topK": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits[0].fileName").value("packaging.md"))
                .andExpect(jsonPath("$.data.hits[0].preview").value(org.hamcrest.Matchers.containsString("Launch4j")));

        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .singleElement()
                .satisfies(document -> assertThat(document.indexedAt()).isNotNull());
    }

    @Test
    void rebuildUsesSqliteAsSourceOfTruth() throws Exception {
        Files.writeString(tempDir.resolve("rag.txt"), "SQLite is the source of truth and Lucene can be rebuilt.");
        ingestionService.ingestFolder(tempDir.toString(), true);
        knowledgeStore.rebuildAll();

        mockMvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedDocumentCount").value(1))
                .andExpect(jsonPath("$.data.indexedChunkCount").value(1))
                .andExpect(jsonPath("$.data.unindexedDocumentCount").value(0));
    }

    @Test
    void deleteDocumentRemovesLuceneHit() throws Exception {
        Files.writeString(tempDir.resolve("delete-search.txt"), "This deleted document should not be searchable.");
        ingestionService.ingestFolder(tempDir.toString(), true);
        KnowledgeDocument document = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();

        mockMvc.perform(delete("/api/documents/{id}", document.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "searchable",
                                  "mode": "KEYWORD",
                                  "topK": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(0));
    }

    @Test
    void vectorAndHybridSearchReturnBadRequestWhenEmbeddingIsUnavailable() throws Exception {
        Files.writeString(tempDir.resolve("keyword-only.txt"), "Keyword search works without an embedding model.");
        ingestionService.ingestFolder(tempDir.toString(), true);

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "embedding",
                                  "mode": "VECTOR"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("vector search is unavailable")));

        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "query": "embedding",
                                  "mode": "HYBRID"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("hybrid search is unavailable")));
    }
}


