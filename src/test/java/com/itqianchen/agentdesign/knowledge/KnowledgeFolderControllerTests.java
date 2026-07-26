package com.itqianchen.agentdesign.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "server.address=127.0.0.1"
})
class KnowledgeFolderControllerTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearState() {
        databaseCleaner.clearDocuments();
        databaseCleaner.clearKnowledgeFolders();
        knowledgeStore.rebuildAll();
    }

    @Test
    void importFolderListsDocumentsAndGroupsThemByFolder() throws Exception {
        Files.writeString(tempDir.resolve("phase10.md"), "# Phase 10\n\nFolder level knowledge base.");

        importFolder(tempDir);

        mockMvc.perform(get("/api/knowledge-folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders.length()").value(1))
                .andExpect(jsonPath("$.data.folders[0].folderPath").value(tempDir.toAbsolutePath().normalize().toString()))
                .andExpect(jsonPath("$.data.folders[0].documentCount").value(1))
                .andExpect(jsonPath("$.data.folders[0].documents[0].fileName").value("phase10.md"))
                .andExpect(jsonPath("$.data.unassignedDocuments.length()").value(0));
    }

    @Test
    void disabledFolderIsRemovedFromSearchAndEnableRestoresIndex() throws Exception {
        Files.writeString(tempDir.resolve("disable-search.txt"), "folder-toggle-keyword should disappear when disabled.");
        String folderId = importFolder(tempDir);

        searchKeyword("folder-toggle-keyword", 1);

        mockMvc.perform(patch("/api/knowledge-folders/{id}/enabled", folderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        // JSON 编解码在边界层完成，避免序列化细节泄漏到业务对象。
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isNoContent());

        searchKeyword("folder-toggle-keyword", 0);

        mockMvc.perform(patch("/api/knowledge-folders/{id}/enabled", folderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        // JSON 编解码在边界层完成，避免序列化细节泄漏到业务对象。
                        .content(objectMapper.writeValueAsString(Map.of("enabled", true))))
                .andExpect(status().isNoContent());

        searchKeyword("folder-toggle-keyword", 1);
    }

    @Test
    void deleteFolderDeletesOnlyApplicationRecords() throws Exception {
        Path note = tempDir.resolve("keep-local-file.txt");
        Files.writeString(note, "Deleting a knowledge folder must not delete the user's local file.");
        String folderId = importFolder(tempDir);

        mockMvc.perform(delete("/api/knowledge-folders/{id}", folderId))
                .andExpect(status().isNoContent());

        assertThat(note).exists();
        mockMvc.perform(get("/api/knowledge-folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders.length()").value(0));
        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        searchKeyword("local file", 0);
    }

    @Test
    void deleteFolderCleansOnlyItsMaintenanceRuns() throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("first"));
        Path second = Files.createDirectory(tempDir.resolve("second"));
        Files.writeString(first.resolve("first.txt"), "first-maintenance-token");
        Files.writeString(second.resolve("second.txt"), "second-maintenance-token");
        String firstFolderId = importFolder(first);
        String secondFolderId = importFolder(second);

        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", firstFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(delete("/api/knowledge-folders/{id}", firstFolderId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", firstFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", secondFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].operation").value("IMPORT"));
        searchKeyword("second-maintenance-token", 1);
    }

    @Test
    void smokeImportSearchHealthDeleteAndRunCleanup() throws Exception {
        Files.writeString(tempDir.resolve("smoke.txt"), "phase32-smoke-token");
        String folderId = importFolder(tempDir);

        searchKeyword("phase32-smoke-token", 1);
        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.indexConsistent").value(true));

        mockMvc.perform(delete("/api/knowledge-folders/{id}", folderId))
                .andExpect(status().isNoContent());

        searchKeyword("phase32-smoke-token", 0);
        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void syncFolderIndexesOnlyNewFiles() throws Exception {
        Files.writeString(tempDir.resolve("first.txt"), "synckeepone");
        Files.writeString(tempDir.resolve("second.txt"), "synckeeptwo");
        String folderId = importFolder(tempDir);

        Files.writeString(tempDir.resolve("third.txt"), "synconlynewtoken");
        mockMvc.perform(post("/api/knowledge-folders/{id}/sync", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scannedCount").value(3))
                .andExpect(jsonPath("$.data.parsedCount").value(1))
                .andExpect(jsonPath("$.data.skippedCount").value(2));

        mockMvc.perform(get("/api/knowledge-folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[0].documentCount").value(3));
        searchKeyword("synconlynewtoken", 1);
        searchKeyword("synckeepone", 1);
    }

    @Test
    void rebuildFolderRemovesDocumentsDeletedFromLocalDirectory() throws Exception {
        Path keep = tempDir.resolve("keep.txt");
        Path stale = tempDir.resolve("stale.txt");
        Files.writeString(keep, "remainingonlytoken");
        Files.writeString(stale, "obsoleteonlytoken");
        String folderId = importFolder(tempDir);

        Files.delete(stale);
        mockMvc.perform(post("/api/knowledge-folders/{id}/rebuild", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedDocumentCount").value(1));

        mockMvc.perform(get("/api/knowledge-folders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[0].documentCount").value(1))
                .andExpect(jsonPath("$.data.folders[0].documents[0].fileName").value("keep.txt"));
        searchKeyword("obsoleteonlytoken", 0);
        searchKeyword("remainingonlytoken", 1);
    }

    private String importFolder(Path folder) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/knowledge-folders/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        // JSON 编解码在边界层完成，避免序列化细节泄漏到业务对象。
                        .content(objectMapper.writeValueAsString(Map.of(
                                "folderPath", folder.toAbsolutePath().normalize().toString(),
                                "recursive", true
                ))))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("parsedCount");
        return findFolderIdByPath(folder);
    }

    private String findFolderIdByPath(Path folder) throws Exception {
        String normalizedPath = folder.toAbsolutePath().normalize().toString();
        MvcResult result = mockMvc.perform(get("/api/knowledge-folders"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode folders = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("folders");
        for (JsonNode node : folders) {
            if (normalizedPath.equals(node.path("folderPath").asText())) {
                return node.path("id").asText();
            }
        }
        throw new AssertionError("Imported folder was not listed: " + normalizedPath);
    }

    private void searchKeyword(String query, int expectedHitCount) throws Exception {
        mockMvc.perform(post("/api/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        // JSON 编解码在边界层完成，避免序列化细节泄漏到业务对象。
                        .content(objectMapper.writeValueAsString(Map.of(
                                "query", query,
                                "mode", "KEYWORD",
                                "topK", 5
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hits.length()").value(expectedHitCount));
    }
}
