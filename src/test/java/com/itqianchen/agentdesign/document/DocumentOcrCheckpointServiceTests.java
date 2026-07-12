package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.mapper.document.DocumentMapper;
import com.itqianchen.agentdesign.mapper.document.DocumentOcrCheckpointMapper;
import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import com.itqianchen.agentdesign.repository.document.DocumentOcrCheckpointRepository;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentOcrCheckpointService;
import com.itqianchen.agentdesign.service.document.DocumentIngestionPersistence;
import com.itqianchen.agentdesign.service.system.DatabaseSchemaInitializer;
import java.util.List;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.sqlite.SQLiteDataSource;

class DocumentOcrCheckpointServiceTests {

    @Test
    void savedPagesCanBeRestoredAndSignatureChangeClearsProgress() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            DocumentOcrCheckpointRepository checkpointRepository = new DocumentOcrCheckpointRepository(
                    sqlSession.getMapper(DocumentOcrCheckpointMapper.class)
            );
            DocumentRepository documentRepository = new DocumentRepository(
                    sqlSession.getMapper(DocumentMapper.class),
                    checkpointRepository
            );
            DocumentOcrCheckpointService firstService = new DocumentOcrCheckpointService(
                    checkpointRepository,
                    documentRepository
            );
            KnowledgeDocument placeholder = placeholder();

            assertThat(firstService.prepare(placeholder, "signature-a", 3)).isEmpty();
            firstService.savePage(placeholder.id(), new ParsedSection("page one", null, 1));

            DocumentOcrCheckpointService restartedService = new DocumentOcrCheckpointService(
                    checkpointRepository,
                    documentRepository
            );
            List<ParsedSection> restored = restartedService.prepare(placeholder, "signature-a", 3);
            assertThat(restored).singleElement().satisfies(section -> {
                assertThat(section.pageNumber()).isEqualTo(1);
                assertThat(section.content()).isEqualTo("page one");
            });
            assertThat(restartedService.exists(placeholder.id(), placeholder.contentHash())).isTrue();

            assertThat(restartedService.prepare(placeholder, "signature-b", 3)).isEmpty();
            restartedService.savePage(placeholder.id(), new ParsedSection("new page", null, 1));
            assertThat(restartedService.prepare(placeholder("changed-content-hash"), "signature-b", 3)).isEmpty();
        }
    }

    @Test
    void emptyPageIsPersistedAsCompleted() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            DocumentOcrCheckpointRepository checkpointRepository = new DocumentOcrCheckpointRepository(
                    sqlSession.getMapper(DocumentOcrCheckpointMapper.class)
            );
            DocumentRepository documentRepository = new DocumentRepository(
                    sqlSession.getMapper(DocumentMapper.class),
                    checkpointRepository
            );
            DocumentOcrCheckpointService service = new DocumentOcrCheckpointService(
                    checkpointRepository,
                    documentRepository
            );
            KnowledgeDocument placeholder = placeholder();

            service.prepare(placeholder, "signature", 1);
            service.savePage(placeholder.id(), new ParsedSection("", null, 1));

            assertThat(service.prepare(placeholder, "signature", 1))
                    .singleElement()
                    .satisfies(section -> assertThat(section.content()).isEmpty());
        }
    }

    @Test
    void completeDocumentPersistenceClearsCheckpoint() {
        try (SqlSession sqlSession = sqliteSqlSession()) {
            new DatabaseSchemaInitializer(sqlSession.getMapper(DatabaseSchemaMapper.class)).initialize();
            DocumentOcrCheckpointRepository checkpointRepository = new DocumentOcrCheckpointRepository(
                    sqlSession.getMapper(DocumentOcrCheckpointMapper.class)
            );
            DocumentRepository documentRepository = new DocumentRepository(
                    sqlSession.getMapper(DocumentMapper.class),
                    checkpointRepository
            );
            DocumentOcrCheckpointService service = new DocumentOcrCheckpointService(
                    checkpointRepository,
                    documentRepository
            );
            KnowledgeDocument placeholder = placeholder();
            service.prepare(placeholder, "signature", 1);
            service.savePage(placeholder.id(), new ParsedSection("page one", null, 1));
            long now = System.currentTimeMillis();
            KnowledgeDocument parsed = new KnowledgeDocument(
                    placeholder.id(),
                    placeholder.knowledgeFolderId(),
                    placeholder.sourcePath(),
                    placeholder.fileName(),
                    placeholder.fileType(),
                    placeholder.fileSize(),
                    placeholder.lastModified(),
                    placeholder.contentHash(),
                    DocumentStatus.PARSED,
                    null,
                    placeholder.createdAt(),
                    now,
                    1
            );
            KnowledgeChunk chunk = new KnowledgeChunk(
                    "chunk-1",
                    placeholder.id(),
                    0,
                    "page one",
                    "chunk-hash",
                    1,
                    null,
                    2,
                    now
            );

            new DocumentIngestionPersistence(documentRepository, checkpointRepository)
                    .replaceParsedDocument(parsed, List.of(chunk));

            assertThat(service.exists(placeholder.id(), placeholder.contentHash())).isFalse();
            assertThat(documentRepository.findChunksByDocumentId(placeholder.id()))
                    .singleElement()
                    .satisfies(savedChunk -> assertThat(savedChunk.content()).isEqualTo("page one"));
        }
    }

    private static KnowledgeDocument placeholder() {
        return placeholder("content-hash");
    }

    private static KnowledgeDocument placeholder(String contentHash) {
        long now = System.currentTimeMillis();
        return new KnowledgeDocument(
                "document-1",
                "folder-1",
                "D:/documents/scan.pdf",
                "scan.pdf",
                FileType.PDF,
                100,
                now,
                contentHash,
                DocumentStatus.OCR_REQUIRED,
                null,
                now,
                now,
                0
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
}
