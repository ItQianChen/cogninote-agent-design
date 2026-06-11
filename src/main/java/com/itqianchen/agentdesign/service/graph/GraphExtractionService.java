package com.itqianchen.agentdesign.service.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphChunkExtraction;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphExtractionStatus;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphPromptProperties;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单 chunk 模型抽取服务。
 * <p>模型调用是唯一昂贵步骤，因此缓存命中只跳过模型调用，merge 仍会从缓存全量重跑。</p>
 */
@Service
public class GraphExtractionService {

    private static final Logger log = LoggerFactory.getLogger(GraphExtractionService.class);
    private static final int MAX_NODES_PER_CHUNK = 24;
    private static final int MAX_EDGES_PER_CHUNK = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 280;
    private static final int MAX_QUOTE_LENGTH = 260;
    private static final int DB_PROGRESS_FLUSH_CHUNKS = 10;
    private static final long DB_PROGRESS_FLUSH_MILLIS = 5_000L;

    private final KnowledgeGraphRepository repository;
    private final KnowledgeGraphRunPublisher publisher;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final GraphCanonicalizer canonicalizer;
    private final ObjectMapper objectMapper;
    private final KnowledgeGraphPromptProperties promptProperties;

    public GraphExtractionService(
            KnowledgeGraphRepository repository,
            KnowledgeGraphRunPublisher publisher,
            AiRuntimeFactory aiRuntimeFactory,
            GraphCanonicalizer canonicalizer,
            ObjectMapper objectMapper,
            KnowledgeGraphPromptProperties promptProperties
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.canonicalizer = canonicalizer;
        this.objectMapper = objectMapper;
        this.promptProperties = promptProperties;
    }

    public GraphExtractionResult extract(
            String runId,
            List<IndexedDocument> documents,
            ModelConfig chatConfig
    ) {
        List<ChunkWorkItem> chunks = flatten(documents);
        int processed = 0;
        int skipped = 0;
        int failed = 0;
        int sinceLastFlush = 0;
        long lastFlushAt = System.currentTimeMillis();

        for (ChunkWorkItem item : chunks) {
            if (publisher.isCancelled(runId)) {
                persistProgress(runId, processed, skipped, failed);
                return new GraphExtractionResult(processed, skipped, failed, true);
            }

            try {
                if (isCacheHit(item.chunk(), chatConfig.id())) {
                    skipped++;
                } else {
                    extractOne(runId, item, chatConfig);
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("knowledge_graph_chunk_extract_failed runId={} chunkId={} reason={}",
                        runId,
                        item.chunk().id(),
                        ex.getMessage()
                );
                log.debug("knowledge_graph_chunk_extract_failed_stacktrace runId={} chunkId={}",
                        runId,
                        item.chunk().id(),
                        ex
                );
                saveFailedExtraction(item.chunk(), ex.getMessage(), chatConfig.id());
            }

            processed++;
            sinceLastFlush++;
            KnowledgeGraphRunProgress progress = new KnowledgeGraphRunProgress(
                    runId,
                    "RUNNING",
                    "EXTRACTING",
                    chunks.size(),
                    processed,
                    skipped,
                    failed
            );
            publisher.publishProgress(runId, progress);

            long now = System.currentTimeMillis();
            if (sinceLastFlush >= DB_PROGRESS_FLUSH_CHUNKS || now - lastFlushAt >= DB_PROGRESS_FLUSH_MILLIS) {
                repository.updateRunProgress(runId, processed, skipped, failed, now);
                sinceLastFlush = 0;
                lastFlushAt = now;
            }
        }

        persistProgress(runId, processed, skipped, failed);
        return new GraphExtractionResult(processed, skipped, failed, false);
    }

    public int countChunks(List<IndexedDocument> documents) {
        return flatten(documents).size();
    }

    public String promptVersion() {
        return promptProperties.extraction().version();
    }

    private void extractOne(String runId, ChunkWorkItem item, ModelConfig chatConfig) {
        String response = aiRuntimeFactory.chatRuntime(chatConfig)
                .callText(promptProperties.extraction().system(), userPrompt(item));
        String json = extractJson(response);
        GraphExtractionPayload payload = parsePayload(json);
        GraphExtractionPayload sanitized = sanitize(payload);
        try {
            String sanitizedJson = objectMapper.writeValueAsString(sanitized);
            long now = System.currentTimeMillis();
            repository.upsertChunkExtraction(new KnowledgeGraphChunkExtraction(
                    item.chunk().id(),
                    item.chunk().documentId(),
                    item.chunk().contentHash(),
                    promptVersion(),
                    chatConfig.id(),
                    KnowledgeGraphExtractionStatus.EXTRACTED,
                    sanitizedJson,
                    null,
                    now
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("failed to serialize graph extraction", ex);
        }
        log.debug("knowledge_graph_chunk_extracted runId={} chunkId={}", runId, item.chunk().id());
    }

    private boolean isCacheHit(IndexedChunk chunk, String modelConfigId) {
        return repository.findExtractionByChunkId(chunk.id())
                .filter(extraction -> extraction.status() == KnowledgeGraphExtractionStatus.EXTRACTED)
                .filter(extraction -> Objects.equals(extraction.contentHash(), chunk.contentHash()))
                .filter(extraction -> Objects.equals(extraction.promptVersion(), promptVersion()))
                .filter(extraction -> Objects.equals(extraction.modelConfigId(), modelConfigId))
                .isPresent();
    }

    private void saveFailedExtraction(IndexedChunk chunk, String message, String modelConfigId) {
        long now = System.currentTimeMillis();
        repository.upsertChunkExtraction(new KnowledgeGraphChunkExtraction(
                chunk.id(),
                chunk.documentId(),
                chunk.contentHash(),
                promptVersion(),
                modelConfigId,
                KnowledgeGraphExtractionStatus.FAILED,
                null,
                canonicalizer.displayText(message, 600),
                now
        ));
    }

    private GraphExtractionPayload parsePayload(String json) {
        try {
            return objectMapper.readValue(json, GraphExtractionPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid graph extraction json", ex);
        }
    }

    private GraphExtractionPayload sanitize(GraphExtractionPayload payload) {
        List<GraphExtractionPayload.Node> nodes = new ArrayList<>();
        for (GraphExtractionPayload.Node node : nullToEmpty(payload.nodes())) {
            if (nodes.size() >= MAX_NODES_PER_CHUNK) {
                break;
            }
            String name = canonicalizer.displayText(node.name(), 120);
            if (name.isBlank()) {
                continue;
            }
            nodes.add(new GraphExtractionPayload.Node(
                    name,
                    canonicalizer.nodeType(node.type()),
                    canonicalizer.displayText(node.description(), MAX_DESCRIPTION_LENGTH),
                    normalizeConfidence(node.confidence()),
                    canonicalizer.displayText(node.quote(), MAX_QUOTE_LENGTH)
            ));
        }

        List<GraphExtractionPayload.Edge> edges = new ArrayList<>();
        for (GraphExtractionPayload.Edge edge : nullToEmpty(payload.edges())) {
            if (edges.size() >= MAX_EDGES_PER_CHUNK) {
                break;
            }
            String source = canonicalizer.displayText(edge.source(), 120);
            String target = canonicalizer.displayText(edge.target(), 120);
            if (source.isBlank() || target.isBlank()) {
                continue;
            }
            edges.add(new GraphExtractionPayload.Edge(
                    source,
                    target,
                    canonicalizer.relationType(edge.type()),
                    canonicalizer.displayText(edge.description(), MAX_DESCRIPTION_LENGTH),
                    normalizeConfidence(edge.confidence()),
                    canonicalizer.displayText(edge.quote(), MAX_QUOTE_LENGTH)
            ));
        }
        return new GraphExtractionPayload(nodes, edges);
    }

    private void persistProgress(String runId, int processed, int skipped, int failed) {
        repository.updateRunProgress(runId, processed, skipped, failed, System.currentTimeMillis());
    }

    private static List<ChunkWorkItem> flatten(List<IndexedDocument> documents) {
        List<ChunkWorkItem> chunks = new ArrayList<>();
        for (IndexedDocument document : documents) {
            for (IndexedChunk chunk : document.chunks()) {
                chunks.add(new ChunkWorkItem(document, chunk));
            }
        }
        return chunks;
    }

    private static String extractJson(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("graph extraction response is blank");
        }
        String stripped = response.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("graph extraction response does not contain json object");
        }
        return stripped.substring(start, end + 1);
    }

    private static double normalizeConfidence(Double confidence) {
        if (confidence == null || confidence.isNaN()) {
            return 0.0;
        }
        return Math.clamp(confidence, 0.0, 1.0);
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String userPrompt(ChunkWorkItem item) {
        IndexedDocument document = item.document();
        IndexedChunk chunk = item.chunk();
        return promptProperties.extraction().user()
                .replace("{documentName}", fallbackText(document.fileName()))
                .replace("{chunkId}", fallbackText(chunk.id()))
                .replace("{heading}", fallbackText(chunk.heading()))
                .replace("{pageNumber}", chunk.pageNumber() == null ? "无" : chunk.pageNumber().toString())
                .replace("{content}", fallbackText(chunk.content()));
    }

    private static String fallbackText(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private record ChunkWorkItem(IndexedDocument document, IndexedChunk chunk) {
    }
}
