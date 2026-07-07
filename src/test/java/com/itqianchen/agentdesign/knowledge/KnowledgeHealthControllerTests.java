package com.itqianchen.agentdesign.knowledge;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRepository;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-knowledge-health",
        "app.storage.database-path=target/test-cogninote-knowledge-health/cogninote.db",
        "server.address=127.0.0.1"
})
class KnowledgeHealthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private KnowledgeFolderRepository folderRepository;

    @Autowired
    private DocumentRepository documentRepository;

    private final DocumentIdentity documentIdentity = new DocumentIdentity();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearState() {
        databaseCleaner.clearDocuments();
        databaseCleaner.clearKnowledgeFolders();
        knowledgeStore.rebuildAll();
    }

    @Test
    void healthReturnsTrustSnapshotAfterImport() throws Exception {
        Files.writeString(tempDir.resolve("healthy.md"), "# Healthy\n\nhealthchecktoken");

        String folderId = importFolder(tempDir);

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.summary.folderCount").value(1))
                .andExpect(jsonPath("$.data.summary.documentCount").value(1))
                .andExpect(jsonPath("$.data.summary.luceneDocumentCount").value(1))
                .andExpect(jsonPath("$.data.summary.luceneChunkCount").value(1))
                .andExpect(jsonPath("$.data.summary.embeddingConfigured").value(false))
                .andExpect(jsonPath("$.data.summary.indexConsistent").value(true))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("EMBEDDING_UNCONFIGURED")))
                .andExpect(jsonPath("$.data.folders[0].id").value(folderId))
                .andExpect(jsonPath("$.data.folders[0].status").value("HEALTHY"))
                .andExpect(jsonPath("$.data.folders[0].lastRun.operation").value("IMPORT"));
    }

    @Test
    void healthReportsLuceneIndexInconsistentWhenIndexedDocumentIsMissing() throws Exception {
        Files.writeString(tempDir.resolve("inconsistent.md"), "# Inconsistent\n\nlucene-drift-token");
        String folderId = importFolder(tempDir);
        KnowledgeDocument document = documentRepository.findByKnowledgeFolderIdOrderByUpdatedAtDesc(folderId)
                .get(0);

        knowledgeStore.deleteByDocumentId(document.id());

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ERROR"))
                .andExpect(jsonPath("$.data.summary.luceneDocumentCount").value(0))
                .andExpect(jsonPath("$.data.summary.luceneChunkCount").value(0))
                .andExpect(jsonPath("$.data.summary.indexConsistent").value(false))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("INDEX_INCONSISTENT")));
    }

    @Test
    void folderHealthReportsMissingLocalFile() throws Exception {
        Path note = tempDir.resolve("missing-after-import.txt");
        Files.writeString(note, "this file will disappear after import");
        String folderId = importFolder(tempDir);

        Files.delete(note);

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.issues[0].code").value("MISSING_LOCAL_FILES"))
                .andExpect(jsonPath("$.data.missingLocalFiles.length()").value(1))
                .andExpect(jsonPath("$.data.missingLocalFiles[0].fileName").value("missing-after-import.txt"));
    }

    @Test
    void folderHealthReportsPdfOcrRequiredWithoutGenericParseFailure() throws Exception {
        long now = System.currentTimeMillis();
        String folderId = "folder-ocr-required";
        folderRepository.upsert(new KnowledgeFolder(
                folderId,
                tempDir.toAbsolutePath().normalize().toString(),
                "OCR Required Folder",
                true,
                true,
                now,
                now,
                now,
                now
        ));
        upsertProblemDocument(folderId, "scanned.pdf", FileType.PDF, DocumentStatus.OCR_REQUIRED, now);

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.issues.length()").value(1))
                .andExpect(jsonPath("$.data.issues[0].code").value("PDF_OCR_REQUIRED"))
                .andExpect(jsonPath("$.data.failedDocuments.length()").value(1))
                .andExpect(jsonPath("$.data.failedDocuments[0].message").value(startsWith("该 PDF 没有可抽取文本层")));

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.failedCount").value(1))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("PDF_OCR_REQUIRED")))
                .andExpect(jsonPath("$.data.issues[*].code", not(hasItem("PARSE_FAILED"))));
    }

    @Test
    void folderHealthSeparatesPdfOcrRequiredFromParseFailed() throws Exception {
        long now = System.currentTimeMillis();
        String folderId = "folder-mixed-failures";
        folderRepository.upsert(new KnowledgeFolder(
                folderId,
                tempDir.toAbsolutePath().normalize().toString(),
                "Mixed Failures Folder",
                true,
                true,
                now,
                now,
                now,
                now
        ));
        upsertProblemDocument(folderId, "scanned.pdf", FileType.PDF, DocumentStatus.OCR_REQUIRED, now);
        upsertProblemDocument(folderId, "broken.txt", FileType.TEXT, DocumentStatus.FAILED, now);

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("PDF_OCR_REQUIRED")))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("PARSE_FAILED")))
                .andExpect(jsonPath("$.data.failedDocuments.length()").value(2));

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.failedCount").value(2))
                .andExpect(jsonPath("$.data.folders[0].failedCount").value(2))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("PDF_OCR_REQUIRED")))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("PARSE_FAILED")));
    }

    @Test
    void folderHealthReportsNewLocalFileAfterImport() throws Exception {
        Files.writeString(tempDir.resolve("imported.md"), "# Imported\n\nalready in knowledge base");
        String folderId = importFolder(tempDir);

        Files.writeString(tempDir.resolve("new-local.md"), "# New\n\nnot synced yet");

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("NEW_LOCAL_FILES")))
                .andExpect(jsonPath("$.data.newLocalFiles.length()").value(1))
                .andExpect(jsonPath("$.data.newLocalFiles[0].fileName").value("new-local.md"));

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.summary.newLocalFileCount").value(1))
                .andExpect(jsonPath("$.data.folders[0].newLocalFileCount").value(1))
                .andExpect(jsonPath("$.data.issues[*].code", hasItem("NEW_LOCAL_FILES")));
    }

    @Test
    void folderHealthTreatsInvalidStoredPathAsMissingLocalFile() throws Exception {
        long now = System.currentTimeMillis();
        String folderId = "folder-invalid-path";
        folderRepository.upsert(new KnowledgeFolder(
                folderId,
                tempDir.toAbsolutePath().normalize().toString(),
                "Invalid Path Folder",
                true,
                true,
                now,
                now,
                now,
                now
        ));
        documentRepository.upsertDocument(new KnowledgeDocument(
                "document-invalid-path",
                folderId,
                "bad" + '\0' + "path.txt",
                "bad-path.txt",
                FileType.TEXT,
                1,
                now,
                "hash-invalid-path",
                DocumentStatus.PARSED,
                now,
                now,
                now,
                1
        ));

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.issues[0].code").value("MISSING_LOCAL_FILES"))
                .andExpect(jsonPath("$.data.missingLocalFiles.length()").value(1))
                .andExpect(jsonPath("$.data.missingLocalFiles[0].fileName").value("bad-path.txt"))
                .andExpect(jsonPath("$.data.missingLocalFiles[0].message").value(startsWith("无法访问本地文件：")));
    }

    @Test
    void disabledFolderDoesNotDegradeOverallHealth() throws Exception {
        Path disabledFile = tempDir.resolve("disabled.md");
        Files.writeString(disabledFile, "# Disabled\n\ninactive folder content");
        String disabledFolderId = importFolder(tempDir);

        mockMvc.perform(patch("/api/knowledge-folders/{id}/enabled", disabledFolderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", false))))
                .andExpect(status().isNoContent());

        Path enabledFolder = Files.createDirectory(tempDir.resolve("enabled"));
        Files.writeString(enabledFolder.resolve("healthy.md"), "# Healthy\n\nactive folder content");
        importFolder(enabledFolder);

        mockMvc.perform(get("/api/knowledge-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING"))
                .andExpect(jsonPath("$.data.summary.folderCount").value(2))
                .andExpect(jsonPath("$.data.summary.enabledFolderCount").value(1))
                .andExpect(jsonPath("$.data.summary.documentCount").value(1))
                .andExpect(jsonPath("$.data.summary.indexConsistent").value(true))
                .andExpect(jsonPath("$.data.summary.unindexedCount").value(0))
                .andExpect(jsonPath("$.data.issues.length()").value(1))
                .andExpect(jsonPath("$.data.issues[0].code").value("EMBEDDING_UNCONFIGURED"));

        mockMvc.perform(get("/api/knowledge-health/folders/{id}", disabledFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.issues.length()").value(0))
                .andExpect(jsonPath("$.data.unindexedDocuments.length()").value(0));
    }

    @Test
    void runsEndpointReturnsFolderMaintenanceHistory() throws Exception {
        Files.writeString(tempDir.resolve("history.md"), "# History\n\nrunhistorytoken");
        String folderId = importFolder(tempDir);

        mockMvc.perform(get("/api/knowledge-health/runs")
                        .queryParam("scopeType", "KNOWLEDGE_FOLDER")
                        .queryParam("scopeId", folderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].operation").value("IMPORT"))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));
    }

    private String importFolder(Path folder) throws Exception {
        mockMvc.perform(post("/api/knowledge-folders/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "folderPath", folder.toAbsolutePath().normalize().toString(),
                                "recursive", true
                        ))))
                .andExpect(status().isOk());
        return databaseCleaner.findAnyKnowledgeFolderId();
    }

    private void upsertProblemDocument(
            String folderId,
            String fileName,
            FileType fileType,
            DocumentStatus status,
            long now
    ) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, "placeholder");
        Path normalizedPath = path.toAbsolutePath().normalize();
        documentRepository.upsertDocument(new KnowledgeDocument(
                documentIdentity.idForPath(normalizedPath.toString()),
                folderId,
                normalizedPath.toString(),
                fileName,
                fileType,
                Files.size(path),
                Files.getLastModifiedTime(path).toMillis(),
                "hash-" + fileName,
                status,
                null,
                now,
                now,
                0
        ));
    }
}
