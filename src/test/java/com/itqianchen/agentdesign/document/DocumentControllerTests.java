package com.itqianchen.agentdesign.document;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.domain.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Document 控制器 测试 承担 文档管理 模块的主要职责。
 * <p>注释说明维护边界，不改变现有运行逻辑。</p>
 */
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
    private TestDatabaseCleaner databaseCleaner;

    @TempDir
    private Path tempDir;

    /**
     * 清理 clear Database 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearDocuments();
    }

    /**
     * 删除 delete Document Returns No Content Or Not Found 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    @Test
    void deleteDocumentReturnsNoContentOrNotFound() throws Exception {
        Path note = tempDir.resolve("delete-api.txt");
        // 文件系统访问可能抛出 IO 异常，调用方需要保留失败上下文。
        Files.writeString(note, "Delete through the REST API.");
        ingestionService.ingestFolder(tempDir.toString(), true);
        KnowledgeDocument document = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();

        mockMvc.perform(delete("/api/documents/{id}", document.id()))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/documents/{id}", document.id()))
                .andExpect(status().isNotFound());
    }

    /**
     * 查询 get Chunk Returns Stored Content 对应的数据。
     * <p>来源预览依赖完整 chunk 正文，避免前端只能展示搜索摘要。</p>
     */
    @Test
    void getChunkReturnsStoredContent() throws Exception {
        Path note = tempDir.resolve("chunk-detail.txt");
        // 文件系统访问可能抛出 IO 异常，调用方需要保留失败上下文。
        Files.writeString(note, "Full chunk detail text should be returned by the document chunk endpoint.");
        ingestionService.ingestFolder(tempDir.toString(), true);
        KnowledgeDocument document = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();
        KnowledgeChunk chunk = documentRepository.findChunksByDocumentId(document.id()).getFirst();

        mockMvc.perform(get("/api/documents/chunks/{chunkId}", chunk.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chunkId").value(chunk.id()))
                .andExpect(jsonPath("$.data.fileName").value("chunk-detail.txt"))
                .andExpect(jsonPath("$.data.content").value(chunk.content()));
    }
}
