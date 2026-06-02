package com.itqianchen.agentdesign.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-document-controller",
        "app.storage.database-path=target/test-cogninote-document-controller/cogninote.db",
        "server.address=127.0.0.1"
})
class DocumentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM chunks");
        jdbcTemplate.update("DELETE FROM documents");
    }

    @Test
    void deleteDocumentReturnsNoContentOrNotFound() throws Exception {
        Path note = tempDir.resolve("delete-api.txt");
        Files.writeString(note, "Delete through the REST API.");
        ingestionService.ingestFolder(tempDir.toString(), true);
        KnowledgeDocument document = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();

        mockMvc.perform(delete("/api/documents/{id}", document.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/documents/{id}", document.id()))
                .andExpect(status().isNotFound());
    }
}
