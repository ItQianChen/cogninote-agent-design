package com.itqianchen.agentdesign.service.search;

import com.itqianchen.agentdesign.domain.interfaces.search.EmbeddingGateway;
import com.itqianchen.agentdesign.domain.exception.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.domain.vo.search.IndexStatistics;
import com.itqianchen.agentdesign.domain.vo.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.vo.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.exception.search.SearchIndexException;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.properties.search.SearchProperties;
import com.itqianchen.agentdesign.domain.vo.search.StoredChunk;
import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import com.itqianchen.agentdesign.domain.dto.index.IndexStatusResponse;
import com.itqianchen.agentdesign.domain.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.dto.search.SearchHitResponse;
import com.itqianchen.agentdesign.domain.dto.search.SearchRequest;
import com.itqianchen.agentdesign.domain.dto.search.SearchResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureClassifier;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.system.AppStorageInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于 Lucene 的本地知识库索引实现。
 *
 * <p>SQLite 保存可重建的文档事实，Lucene 只保存搜索所需的倒排字段、向量字段和预览字段。
 * 所有写索引入口使用同步块，避免桌面应用内的导入、删除和重建并发写坏同一个索引目录。</p>
 */
@Service
public class LuceneKnowledgeStore implements KnowledgeStore {

    private static final int PREVIEW_LIMIT = 260;
    private static final Logger log = LoggerFactory.getLogger(LuceneKnowledgeStore.class);

    private final DocumentRepository documentRepository;
    private final EmbeddingGateway embeddingGateway;
    private final SearchProperties searchProperties;
    private final AppStorage appStorage;
    private final SearchIndexTextBuilder indexTextBuilder;
    private final DocumentFailureCodec failureCodec;
    private final Analyzer analyzer = new PerFieldAnalyzerWrapper(
            new SmartChineseAnalyzer(),
            Map.of(SearchFieldNames.CODE_CONTENT, new StandardAnalyzer())
    );

    /**
     * 注入索引实现依赖。
     *
     * @param documentRepository SQLite 文档事实源
     * @param embeddingGateway Embedding 能力网关
     * @param searchProperties 检索参数配置
     * @param storageInitializer 应用存储目录初始化器
     * @param indexTextBuilder 索引文本构建器
     * @param failureCodec 失败诊断编解码器
     */
    public LuceneKnowledgeStore(
            DocumentRepository documentRepository,
            EmbeddingGateway embeddingGateway,
            SearchProperties searchProperties,
            AppStorageInitializer storageInitializer,
            SearchIndexTextBuilder indexTextBuilder,
            DocumentFailureCodec failureCodec
    ) {
        this.documentRepository = documentRepository;
        this.embeddingGateway = embeddingGateway;
        this.searchProperties = searchProperties;
        this.appStorage = storageInitializer.appStorage();
        this.indexTextBuilder = indexTextBuilder;
        this.failureCodec = failureCodec;
    }

    /**
     * 索引单个文档并覆盖同 documentId 的旧 chunk。
     *
     * @param document 已持久化的文档索引快照
     */
    @Override
    public synchronized void indexDocument(IndexedDocument document) {
        try {
            ensureIndexDirectory();
            try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
                 IndexWriter writer = newWriter(directory)) {
                replaceDocument(writer, document);
                writer.commit();
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to index document: " + document.fileName(), ex);
        }
    }

    /**
     * 从 Lucene 索引中删除单个文档的所有 chunk。
     *
     * @param documentId 文档 ID
     */
    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        try {
            ensureIndexDirectory();
            try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
                 IndexWriter writer = newWriter(directory)) {
                writer.deleteDocuments(new Term(SearchFieldNames.DOCUMENT_ID, documentId));
                writer.commit();
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to delete document from search index: " + documentId, ex);
        }
    }

    /**
     * 重建指定文档集合的索引。
     *
     * <p>单个文档失败会清除其 indexedAt 并继续处理后续文档，避免一份坏文件阻断整批目录重建。</p>
     *
     * @param documents 待重建的文档快照
     * @return 重建统计
     */
    @Override
    public synchronized RebuildIndexResponse rebuildByDocumentIds(List<IndexedDocument> documents) {
        long startedAt = System.currentTimeMillis();
        long indexedDocumentCount = 0;
        long indexedChunkCount = 0;
        long failedDocumentCount = 0;
        List<String> indexedDocumentIds = new ArrayList<>();
        List<DocumentFailureResponse> failures = new ArrayList<>();

        try {
            ensureIndexDirectory();
            try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
                 IndexWriter writer = newWriter(directory)) {
                for (IndexedDocument document : documents) {
                    writer.deleteDocuments(new Term(SearchFieldNames.DOCUMENT_ID, document.id()));
                    try {
                        addDocumentChunks(writer, document);
                        indexedDocumentCount++;
                        indexedChunkCount += document.chunks().size();
                        indexedDocumentIds.add(document.id());
                    } catch (RuntimeException ex) {
                        failedDocumentCount++;
                        documentRepository.clearIndexed(document.id());
                        failures.add(recordIndexFailure(document, ex));
                        logDocumentRebuildFailure(document, ex);
                    }
                }
                writer.commit();
            }
            long indexedAt = System.currentTimeMillis();
            for (String documentId : indexedDocumentIds) {
                documentRepository.markIndexed(documentId, indexedAt);
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to rebuild selected documents", ex);
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        return new RebuildIndexResponse(
                indexedDocumentCount,
                indexedChunkCount,
                failedDocumentCount,
                durationMs,
                List.copyOf(failures)
        );
    }

    /**
     * 执行检索请求。
     *
     * @param request 检索请求
     * @return 检索响应
     */
    @Override
    public SearchResponse search(SearchRequest request) {
        String query = request.query().trim();
        SearchMode mode = request.modeOrDefault();
        int topK = searchProperties.normalizedTopK(request.topK());

        if (!StringUtils.hasText(query)) {
            return new SearchResponse(query, mode, List.of());
        }

        return switch (mode) {
            case KEYWORD -> new SearchResponse(query, mode, toResponseHits(searchKeyword(query, topK)));
            case VECTOR -> new SearchResponse(query, mode, toResponseHits(searchVector(query, topK)));
            case HYBRID -> new SearchResponse(query, mode, toResponseHits(searchHybrid(query, topK)));
        };
    }

    /**
     * 读取索引状态。
     *
     * <p>文档数量以 SQLite 已解析和未索引统计为准，Lucene 只提供 chunk 数，避免索引损坏时反向污染事实源。</p>
     *
     * @return 索引状态响应
     */
    @Override
    public IndexStatusResponse status() {
        IndexStatistics statistics = documentRepository.indexStatistics();
        try {
            ensureIndexDirectory();
            IndexCounts indexCounts = readIndexCounts();
            return new IndexStatusResponse(
                    appStorage.luceneIndexDir().toString(),
                    indexCounts.documentCount(),
                    indexCounts.chunkCount(),
                    statistics.parsedDocumentCount(),
                    statistics.unindexedDocumentCount(),
                    statistics.lastIndexedAt(),
                    embeddingGateway.isAvailable()
            );
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to read search index status", ex);
        }
    }

    /**
     * 按 SQLite 中所有已解析文档全量重建 Lucene 索引。
     *
     * @return 重建统计
     */
    @Override
    public synchronized RebuildIndexResponse rebuildAll() {
        long startedAt = System.currentTimeMillis();
        long indexedDocumentCount = 0;
        long indexedChunkCount = 0;
        long failedDocumentCount = 0;
        List<String> indexedDocumentIds = new ArrayList<>();
        List<DocumentFailureResponse> failures = new ArrayList<>();

        try {
            ensureIndexDirectory();
            List<IndexedDocument> documents = documentRepository.findAllParsedDocumentsForIndexing();
            try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
                 IndexWriter writer = newWriter(directory)) {
                writer.deleteAll();
                for (IndexedDocument document : documents) {
                    try {
                        addDocumentChunks(writer, document);
                        indexedDocumentCount++;
                        indexedChunkCount += document.chunks().size();
                        indexedDocumentIds.add(document.id());
                    } catch (RuntimeException ex) {
                        failedDocumentCount++;
                        documentRepository.clearIndexed(document.id());
                        failures.add(recordIndexFailure(document, ex));
                        logDocumentRebuildFailure(document, ex);
                    }
                }
                writer.commit();
            }
            long indexedAt = System.currentTimeMillis();
            for (String documentId : indexedDocumentIds) {
                documentRepository.markIndexed(documentId, indexedAt);
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to rebuild search index", ex);
        }

        long durationMs = System.currentTimeMillis() - startedAt;
        return new RebuildIndexResponse(
                indexedDocumentCount,
                indexedChunkCount,
                failedDocumentCount,
                durationMs,
                List.copyOf(failures)
        );
    }

    private DocumentFailureResponse recordIndexFailure(IndexedDocument document, RuntimeException exception) {
        Path path;
        try {
            path = Path.of(document.sourcePath());
        } catch (RuntimeException ignored) {
            path = null;
        }
        long now = System.currentTimeMillis();
        DocumentFailureResponse failure = DocumentFailureClassifier.classify(
                path,
                DocumentFailureStage.INDEX,
                exception,
                now
        );
        documentRepository.updateLastFailure(
                document.id(),
                failure,
                failureCodec.encodeContext(failure),
                now
        );
        return failure;
    }

    /**
     * 记录单文档重建失败。
     *
     * @param document 失败文档
     * @param ex 失败异常
     */
    private void logDocumentRebuildFailure(IndexedDocument document, RuntimeException ex) {
        // 单个文档重建失败不应阻断整批索引；SQLite chunks 仍在，后续可再次重建。
        log.warn("document_rebuild_failed documentId={} fileName={}", document.id(), document.fileName(), ex);
    }

    /**
     * 用新文档 chunk 替换旧索引记录。
     *
     * @param writer Lucene 写入器
     * @param document 文档索引快照
     * @throws IOException 当 Lucene 写入失败时抛出
     */
    private void replaceDocument(IndexWriter writer, IndexedDocument document) throws IOException {
        writer.deleteDocuments(new Term(SearchFieldNames.DOCUMENT_ID, document.id()));
        addDocumentChunks(writer, document);
    }

    /**
     * 将文档 chunk 写入 Lucene。
     *
     * <p>向量存在时按 chunk 顺序批量生成并按同一 index 写入，顺序错位会导致搜索结果指向错误内容。</p>
     *
     * @param writer Lucene 写入器
     * @param document 文档索引快照
     * @throws IOException 当 Lucene 写入失败时抛出
     */
    private void addDocumentChunks(IndexWriter writer, IndexedDocument document) throws IOException {
        List<float[]> vectors = List.of();
        if (embeddingGateway.isAvailable()) {
            // 向量列表必须和 chunk 顺序完全一致，下面按相同 index 写入 Lucene 文档。
            vectors = embeddingGateway.embedDocuments(document.chunks().stream()
                    .map(IndexedChunk::content)
                    .toList());
        }

        for (int index = 0; index < document.chunks().size(); index++) {
            IndexedChunk chunk = document.chunks().get(index);
            Document luceneDocument = new Document();
            luceneDocument.add(new StringField(SearchFieldNames.CHUNK_ID, chunk.id(), org.apache.lucene.document.Field.Store.YES));
            luceneDocument.add(new StringField(SearchFieldNames.DOCUMENT_ID, document.id(), org.apache.lucene.document.Field.Store.YES));
            luceneDocument.add(new TextField(SearchFieldNames.FILE_NAME, document.fileName(), org.apache.lucene.document.Field.Store.YES));
            luceneDocument.add(new StoredField(SearchFieldNames.SOURCE_PATH, document.sourcePath()));
            luceneDocument.add(new IntPoint(SearchFieldNames.CHUNK_INDEX, chunk.chunkIndex()));
            luceneDocument.add(new StoredField(SearchFieldNames.CHUNK_INDEX, chunk.chunkIndex()));
            luceneDocument.add(new StringField(SearchFieldNames.CONTENT_HASH, chunk.contentHash(), org.apache.lucene.document.Field.Store.YES));
            SearchIndexText indexText = indexTextBuilder.build(document, chunk);
            luceneDocument.add(new TextField(SearchFieldNames.CONTENT, indexText.proseText(), org.apache.lucene.document.Field.Store.NO));
            luceneDocument.add(new TextField(SearchFieldNames.CODE_CONTENT, indexText.codeText(), org.apache.lucene.document.Field.Store.NO));
            luceneDocument.add(new StoredField(SearchFieldNames.PREVIEW, preview(chunk.content())));

            if (StringUtils.hasText(chunk.heading())) {
                luceneDocument.add(new TextField(SearchFieldNames.HEADING, chunk.heading(), org.apache.lucene.document.Field.Store.YES));
            }
            if (chunk.pageNumber() != null) {
                luceneDocument.add(new StoredField(SearchFieldNames.PAGE_NUMBER, chunk.pageNumber()));
            }
            if (!vectors.isEmpty()) {
                luceneDocument.add(new KnnFloatVectorField(
                        SearchFieldNames.EMBEDDING,
                        vectors.get(index),
                        VectorSimilarityFunction.COSINE
                ));
            }

            writer.addDocument(luceneDocument);
        }
    }

    /**
     * 执行关键词检索。
     *
     * @param query 用户查询
     * @param limit 返回数量上限
     * @return 已补齐存储信息的命中列表
     */
    private List<SearchHit> searchKeyword(String query, int limit) {
        try {
            if (!indexExists()) {
                return List.of();
            }

            return searchWithReader(searcher -> {
                Query keywordQuery = buildKeywordQuery(query);
                TopDocs topDocs = searcher.search(keywordQuery, limit);
                List<SearchHit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(SearchHit.keyword(document, scoreDoc.score));
                }
                return hydrateHits(hits);
            });
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to execute keyword search", ex);
        }
    }

    /**
     * 执行向量检索。
     *
     * @param query 用户查询
     * @param limit 返回数量上限
     * @return 已补齐存储信息的命中列表
     */
    private List<SearchHit> searchVector(String query, int limit) {
        if (!embeddingGateway.isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured; vector search is unavailable");
        }

        float[] queryVector = embeddingGateway.embedQuery(query);
        try {
            if (!indexExists()) {
                return List.of();
            }

            return searchWithReader(searcher -> {
                TopDocs topDocs = searcher.search(
                        new KnnFloatVectorQuery(SearchFieldNames.EMBEDDING, queryVector, limit),
                        limit
                );
                List<SearchHit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document document = searcher.storedFields().document(scoreDoc.doc);
                    hits.add(SearchHit.vector(document, scoreDoc.score));
                }
                return hydrateHits(hits);
            });
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to execute vector search", ex);
        }
    }

    /**
     * 执行混合检索并用 RRF 融合排序。
     *
     * @param query 用户查询
     * @param topK 最终返回数量
     * @return 融合后的命中列表
     */
    private List<SearchHit> searchHybrid(String query, int topK) {
        if (!embeddingGateway.isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured; hybrid search is unavailable");
        }

        // 先扩大候选集再做 RRF 融合，避免 BM25 或向量任一侧的早截断吞掉互补结果。
        int candidateLimit = searchProperties.hybridCandidateLimit(topK);
        List<SearchHit> keywordHits = searchKeyword(query, candidateLimit);
        List<SearchHit> vectorHits = searchVector(query, candidateLimit);
        Map<String, SearchHit> merged = new LinkedHashMap<>();

        for (SearchHit hit : keywordHits) {
            SearchHit current = merged.computeIfAbsent(hit.chunkId(), ignored -> hit.withScores(null, null, 0));
            merged.put(hit.chunkId(), current.withScores(hit.keywordScore(), current.vectorScore(), 0));
        }
        for (SearchHit hit : vectorHits) {
            SearchHit current = merged.computeIfAbsent(hit.chunkId(), ignored -> hit.withScores(null, null, 0));
            merged.put(hit.chunkId(), current.withScores(current.keywordScore(), hit.vectorScore(), 0));
        }

        return merged.values().stream()
                .map(hit -> {
                    double finalScore = rrfScore(hit.chunkId(), keywordHits, vectorHits);
                    return hit.withScores(hit.keywordScore(), hit.vectorScore(), finalScore);
                })
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * 构造可容错的多字段关键词查询。
     *
     * <p>用户可能输入路径片段、代码符号或 Lucene 特殊字符；解析失败时先转义，再退化为单字段
     * TermQuery，保证搜索接口返回明确结果或空结果，而不是把语法错误暴露给前端。</p>
     *
     * @param query 用户查询
     * @return Lucene 查询对象
     */
    private Query buildKeywordQuery(String query) {
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{
                            SearchFieldNames.CONTENT,
                            SearchFieldNames.CODE_CONTENT,
                            SearchFieldNames.HEADING,
                            SearchFieldNames.FILE_NAME
                    },
                    analyzer
            );
            parser.setDefaultOperator(MultiFieldQueryParser.Operator.OR);
            return parser.parse(query);
        } catch (org.apache.lucene.queryparser.classic.ParseException ex) {
            try {
                MultiFieldQueryParser parser = new MultiFieldQueryParser(
                        new String[]{
                                SearchFieldNames.CONTENT,
                                SearchFieldNames.CODE_CONTENT,
                                SearchFieldNames.HEADING,
                                SearchFieldNames.FILE_NAME
                        },
                        analyzer
                );
                return parser.parse(MultiFieldQueryParser.escape(query));
            } catch (org.apache.lucene.queryparser.classic.ParseException nestedEx) {
                return new org.apache.lucene.search.TermQuery(new Term(SearchFieldNames.CONTENT, query));
            }
        }
    }

    /**
     * 用 SQLite 中的完整 chunk 信息补齐搜索命中。
     *
     * <p>Lucene 只存搜索和预览字段，来源路径、标题等事实字段以 SQLite 为准。</p>
     *
     * @param hits Lucene 命中
     * @return 可返回给上层的命中列表
     */
    private List<SearchHit> hydrateHits(List<SearchHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }

        List<String> chunkIds = hits.stream().map(SearchHit::chunkId).toList();
        Map<String, StoredChunk> storedChunks = documentRepository.findStoredChunksByIds(chunkIds)
                .stream()
                .collect(Collectors.toMap(StoredChunk::chunkId, chunk -> chunk));

        return hits.stream()
                .map(hit -> {
                    StoredChunk chunk = storedChunks.get(hit.chunkId());
                    if (chunk == null) {
                        return null;
                    }

                    return hit.withStoredChunk(chunk);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 将内部命中转换为 API 响应。
     *
     * @param hits 内部命中
     * @return API 命中响应
     */
    private List<SearchHitResponse> toResponseHits(List<SearchHit> hits) {
        return hits.stream()
                .map(hit -> new SearchHitResponse(
                        hit.chunkId(),
                        hit.documentId(),
                        hit.fileName(),
                        hit.sourcePath(),
                        hit.heading(),
                        hit.pageNumber(),
                        hit.preview(),
                        hit.score(),
                        hit.keywordScore(),
                        hit.vectorScore()
                ))
                .toList();
    }

    /**
     * 计算单个 chunk 的 RRF 融合分。
     *
     * @param chunkId chunk ID
     * @param keywordHits 关键词命中列表
     * @param vectorHits 向量命中列表
     * @return 融合分
     */
    private double rrfScore(String chunkId, List<SearchHit> keywordHits, List<SearchHit> vectorHits) {
        double keywordScore = reciprocalRankScore(chunkId, keywordHits, searchProperties.bm25Weight());
        double vectorScore = reciprocalRankScore(chunkId, vectorHits, searchProperties.vectorWeight());
        return keywordScore + vectorScore;
    }

    /**
     * 计算指定命中列表中的加权倒数排名分。
     *
     * @param chunkId chunk ID
     * @param hits 命中列表
     * @param weight 该检索通道权重
     * @return 倒数排名分
     */
    private double reciprocalRankScore(String chunkId, List<SearchHit> hits, double weight) {
        int rrfK = searchProperties.normalizedRrfK();
        for (int index = 0; index < hits.size(); index++) {
            if (hits.get(index).chunkId().equals(chunkId)) {
                return weight / (rrfK + index + 1.0);
            }
        }
        return 0.0;
    }

    /**
     * 读取索引中文档和 chunk 计数。
     *
     * @param statistics SQLite 统计信息
     * @return 索引计数
     * @throws IOException 当 Lucene reader 打开失败时抛出
     */
    private IndexCounts readIndexCounts() throws IOException {
        if (!indexExists()) {
            return new IndexCounts(0, 0);
        }

        return searchWithReader(searcher -> {
            Set<String> documentIds = new HashSet<>();
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                Bits liveDocs = leaf.reader().getLiveDocs();
                for (int docId = 0; docId < leaf.reader().maxDoc(); docId++) {
                    if (liveDocs != null && !liveDocs.get(docId)) {
                        continue;
                    }
                    Document document = searcher.storedFields().document(
                            leaf.docBase + docId,
                            Set.of(SearchFieldNames.DOCUMENT_ID)
                    );
                    if (StringUtils.hasText(document.get(SearchFieldNames.DOCUMENT_ID))) {
                        documentIds.add(document.get(SearchFieldNames.DOCUMENT_ID));
                    }
                }
            }
            return new IndexCounts(documentIds.size(), searcher.getIndexReader().numDocs());
        });
    }

    /**
     * 打开 Lucene reader 并执行搜索操作。
     *
     * @param operation 搜索操作
     * @return 搜索结果
     * @throws IOException 当索引读取失败时抛出
     */
    private <T> T searchWithReader(IndexSearchOperation<T> operation) throws IOException {
        try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(bm25Similarity());
            return operation.search(searcher);
        }
    }

    /**
     * 创建 Lucene IndexWriter。
     *
     * <p>目录由调用方持有并关闭。IndexWriter 不拥有传入的 Directory，若只关闭 writer，
     * Windows 上可能残留目录句柄并阻止测试临时目录删除。</p>
     *
     * @param directory 当前写入会话使用的索引目录
     * @return 配置好 analyzer 和 BM25 的写入器
     * @throws IOException 当索引目录无法打开时抛出
     */
    private IndexWriter newWriter(FSDirectory directory) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(bm25Similarity());
        return new IndexWriter(directory, config);
    }

    /**
     * 创建统一 BM25 相似度配置。
     *
     * @return BM25Similarity
     */
    private BM25Similarity bm25Similarity() {
        return new BM25Similarity(searchProperties.bm25K1(), searchProperties.bm25B());
    }

    /**
     * 确保 Lucene 索引目录存在。
     *
     * @throws IOException 当目录无法创建时抛出
     */
    private void ensureIndexDirectory() throws IOException {
        Files.createDirectories(appStorage.luceneIndexDir());
    }

    /**
     * 判断索引目录中是否已有 Lucene 索引。
     *
     * @return 是否存在索引
     * @throws IOException 当索引目录无法读取时抛出
     */
    private boolean indexExists() throws IOException {
        ensureIndexDirectory();
        try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir())) {
            return DirectoryReader.indexExists(directory);
        }
    }

    /**
     * 构造搜索结果预览文本。
     *
     * @param content chunk 原文
     * @return 截断后的单行预览
     */
    private String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT) + "...";
    }

    @FunctionalInterface
    private interface IndexSearchOperation<T> {
        /**
         * 使用已打开的 IndexSearcher 执行搜索。
         *
         * @param searcher Lucene searcher
         * @return 搜索结果
         * @throws IOException 当读取 stored fields 或查询失败时抛出
         */
        T search(IndexSearcher searcher) throws IOException;
    }

    private record IndexCounts(long documentCount, long chunkCount) {
    }

    private record SearchHit(
            String chunkId,
            String documentId,
            String fileName,
            String sourcePath,
            String heading,
            Integer pageNumber,
            String preview,
            double score,
            Double keywordScore,
            Double vectorScore
    ) {
        /**
         * 从关键词命中构建内部命中对象。
         *
         * @param document Lucene 文档
         * @param score BM25 分数
         * @return 内部命中
         */
        private static SearchHit keyword(Document document, double score) {
            return fromDocument(document, score, score, null);
        }

        /**
         * 从向量命中构建内部命中对象。
         *
         * @param document Lucene 文档
         * @param score 向量相似度分数
         * @return 内部命中
         */
        private static SearchHit vector(Document document, double score) {
            return fromDocument(document, score, null, score);
        }

        /**
         * 从 Lucene stored fields 构建内部命中对象。
         *
         * @param document Lucene 文档
         * @param score 当前通道分数
         * @param keywordScore 关键词分数
         * @param vectorScore 向量分数
         * @return 内部命中
         */
        private static SearchHit fromDocument(Document document, double score, Double keywordScore, Double vectorScore) {
            return new SearchHit(
                    document.get(SearchFieldNames.CHUNK_ID),
                    document.get(SearchFieldNames.DOCUMENT_ID),
                    document.get(SearchFieldNames.FILE_NAME),
                    document.get(SearchFieldNames.SOURCE_PATH),
                    document.get(SearchFieldNames.HEADING),
                    storedInteger(document, SearchFieldNames.PAGE_NUMBER),
                    document.get(SearchFieldNames.PREVIEW),
                    score,
                    keywordScore,
                    vectorScore
            );
        }

        /**
         * 用 SQLite chunk 事实字段补齐命中。
         *
         * @param chunk 存储中的 chunk
         * @return 补齐后的命中
         */
        private SearchHit withStoredChunk(StoredChunk chunk) {
            return new SearchHit(
                    chunkId,
                    chunk.documentId(),
                    chunk.fileName(),
                    chunk.sourcePath(),
                    chunk.heading(),
                    chunk.pageNumber(),
                    preview,
                    score,
                    keywordScore,
                    vectorScore
            );
        }

        /**
         * 替换融合后的分数字段。
         *
         * @param keywordScore 关键词分数
         * @param vectorScore 向量分数
         * @param score 最终排序分
         * @return 新命中对象
         */
        private SearchHit withScores(Double keywordScore, Double vectorScore, double score) {
            return new SearchHit(
                    chunkId,
                    documentId,
                    fileName,
                    sourcePath,
                    heading,
                    pageNumber,
                    preview,
                    score,
                    keywordScore,
                    vectorScore
            );
        }

        /**
         * 读取 stored integer 字段。
         *
         * @param document Lucene 文档
         * @param fieldName 字段名
         * @return 整数值；不存在时返回 null
         */
        private static Integer storedInteger(Document document, String fieldName) {
            if (document.getField(fieldName) == null || document.getField(fieldName).numericValue() == null) {
                return null;
            }
            return document.getField(fieldName).numericValue().intValue();
        }
    }
}


