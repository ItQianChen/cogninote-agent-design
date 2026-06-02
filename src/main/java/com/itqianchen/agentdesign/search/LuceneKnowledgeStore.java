package com.itqianchen.agentdesign.search;

import com.itqianchen.agentdesign.document.DocumentRepository;
import com.itqianchen.agentdesign.storage.AppStorage;
import com.itqianchen.agentdesign.storage.AppStorageInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.lucene.analysis.Analyzer;
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
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class LuceneKnowledgeStore implements KnowledgeStore {

    private static final int PREVIEW_LIMIT = 260;
    private static final int MIN_HYBRID_CANDIDATES = 20;

    private final DocumentRepository documentRepository;
    private final EmbeddingGateway embeddingGateway;
    private final SearchProperties searchProperties;
    private final AppStorage appStorage;
    private final Analyzer analyzer = new StandardAnalyzer();

    public LuceneKnowledgeStore(
            DocumentRepository documentRepository,
            EmbeddingGateway embeddingGateway,
            SearchProperties searchProperties,
            AppStorageInitializer storageInitializer
    ) {
        this.documentRepository = documentRepository;
        this.embeddingGateway = embeddingGateway;
        this.searchProperties = searchProperties;
        this.appStorage = storageInitializer.appStorage();
    }

    @Override
    public synchronized void indexDocument(IndexedDocument document) {
        try {
            ensureIndexDirectory();
            try (IndexWriter writer = newWriter()) {
                replaceDocument(writer, document);
                writer.commit();
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to index document: " + document.fileName(), ex);
        }
    }

    @Override
    public synchronized void deleteByDocumentId(String documentId) {
        try {
            ensureIndexDirectory();
            try (IndexWriter writer = newWriter()) {
                writer.deleteDocuments(new Term(SearchFieldNames.DOCUMENT_ID, documentId));
                writer.commit();
            }
        } catch (IOException ex) {
            throw new SearchIndexException("Failed to delete document from search index: " + documentId, ex);
        }
    }

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

    @Override
    public IndexStatusResponse status() {
        IndexStatistics statistics = documentRepository.indexStatistics();
        try {
            ensureIndexDirectory();
            IndexCounts indexCounts = readIndexCounts(statistics);
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

    @Override
    public synchronized RebuildIndexResponse rebuildAll() {
        long startedAt = System.currentTimeMillis();
        long indexedDocumentCount = 0;
        long indexedChunkCount = 0;
        long failedDocumentCount = 0;
        List<String> indexedDocumentIds = new ArrayList<>();

        try {
            ensureIndexDirectory();
            List<IndexedDocument> documents = documentRepository.findAllParsedDocumentsForIndexing();
            try (IndexWriter writer = newWriter()) {
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
        return new RebuildIndexResponse(indexedDocumentCount, indexedChunkCount, failedDocumentCount, durationMs);
    }

    private void replaceDocument(IndexWriter writer, IndexedDocument document) throws IOException {
        writer.deleteDocuments(new Term(SearchFieldNames.DOCUMENT_ID, document.id()));
        addDocumentChunks(writer, document);
    }

    private void addDocumentChunks(IndexWriter writer, IndexedDocument document) throws IOException {
        List<float[]> vectors = List.of();
        if (embeddingGateway.isAvailable()) {
            vectors = embeddingGateway.embedBatch(document.chunks().stream()
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
            luceneDocument.add(new TextField(SearchFieldNames.CONTENT, chunk.content(), org.apache.lucene.document.Field.Store.NO));
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

    private List<SearchHit> searchVector(String query, int limit) {
        if (!embeddingGateway.isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured; vector search is unavailable");
        }

        float[] queryVector = embeddingGateway.embedBatch(List.of(query)).getFirst();
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

    private List<SearchHit> searchHybrid(String query, int topK) {
        if (!embeddingGateway.isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured; hybrid search is unavailable");
        }

        int candidateLimit = Math.max(topK * 3, MIN_HYBRID_CANDIDATES);
        List<SearchHit> keywordHits = searchKeyword(query, candidateLimit);
        List<SearchHit> vectorHits = searchVector(query, candidateLimit);
        Map<String, SearchHit> merged = new LinkedHashMap<>();

        Map<String, Double> normalizedKeywordScores = normalize(keywordHits, ScoreKind.KEYWORD);
        Map<String, Double> normalizedVectorScores = normalize(vectorHits, ScoreKind.VECTOR);
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
                    double keywordScore = normalizedKeywordScores.getOrDefault(hit.chunkId(), 0.0);
                    double vectorScore = normalizedVectorScores.getOrDefault(hit.chunkId(), 0.0);
                    double finalScore = searchProperties.bm25Weight() * keywordScore
                            + searchProperties.vectorWeight() * vectorScore;
                    return hit.withScores(hit.keywordScore(), hit.vectorScore(), finalScore);
                })
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(topK)
                .toList();
    }

    private Query buildKeywordQuery(String query) {
        try {
            MultiFieldQueryParser parser = new MultiFieldQueryParser(
                    new String[]{
                            SearchFieldNames.CONTENT,
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

    private Map<String, Double> normalize(List<SearchHit> hits, ScoreKind scoreKind) {
        Map<String, Double> scores = new HashMap<>();
        List<Double> rawScores = hits.stream()
                .map(hit -> scoreKind == ScoreKind.KEYWORD ? hit.keywordScore() : hit.vectorScore())
                .filter(Objects::nonNull)
                .toList();
        if (rawScores.isEmpty()) {
            return scores;
        }

        double min = rawScores.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        double max = rawScores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        for (SearchHit hit : hits) {
            Double rawScore = scoreKind == ScoreKind.KEYWORD ? hit.keywordScore() : hit.vectorScore();
            if (rawScore == null) {
                continue;
            }

            double normalized = max == min ? 1.0 : (rawScore - min) / (max - min);
            scores.put(hit.chunkId(), normalized);
        }

        return scores;
    }

    private IndexCounts readIndexCounts(IndexStatistics statistics) throws IOException {
        if (!indexExists()) {
            return new IndexCounts(0, 0);
        }

        return searchWithReader(searcher -> {
            long documentCount = Math.max(0L,
                    statistics.parsedDocumentCount() - statistics.unindexedDocumentCount()
            );
            return new IndexCounts(documentCount, searcher.getIndexReader().numDocs());
        });
    }

    private <T> T searchWithReader(IndexSearchOperation<T> operation) throws IOException {
        try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir());
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return operation.search(new IndexSearcher(reader));
        }
    }

    private IndexWriter newWriter() throws IOException {
        return new IndexWriter(
                FSDirectory.open(appStorage.luceneIndexDir()),
                new IndexWriterConfig(analyzer)
        );
    }

    private void ensureIndexDirectory() throws IOException {
        Files.createDirectories(appStorage.luceneIndexDir());
    }

    private boolean indexExists() throws IOException {
        ensureIndexDirectory();
        try (FSDirectory directory = FSDirectory.open(appStorage.luceneIndexDir())) {
            return DirectoryReader.indexExists(directory);
        }
    }

    private String preview(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT) + "...";
    }

    private enum ScoreKind {
        KEYWORD,
        VECTOR
    }

    @FunctionalInterface
    private interface IndexSearchOperation<T> {
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
        private static SearchHit keyword(Document document, double score) {
            return fromDocument(document, score, score, null);
        }

        private static SearchHit vector(Document document, double score) {
            return fromDocument(document, score, null, score);
        }

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

        private static Integer storedInteger(Document document, String fieldName) {
            if (document.getField(fieldName) == null || document.getField(fieldName).numericValue() == null) {
                return null;
            }
            return document.getField(fieldName).numericValue().intValue();
        }
    }
}
