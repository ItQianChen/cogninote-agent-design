package com.itqianchen.agentdesign.service.knowledge;
import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.dto.index.IndexStatusResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderHealthResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderHealthSummaryResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunDeleteResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunDetailResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunPageResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeHealthIssueExampleResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeHealthIssueResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeHealthResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeHealthSummaryResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeProblemDocumentResponse;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeHealthIssueCode;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeHealthStatus;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.vo.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphSummaryRow;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 计算知识库健康快照。
 *
 * <p>健康状态由 SQLite 文档事实、目录配置和轻量文件系统探针即时派生，不额外持久化副本。</p>
 */
@Service
public class KnowledgeHealthService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHealthService.class);

    private static final int DETAIL_RUN_LIMIT = 20;
    private static final int DEFAULT_RUN_PAGE_SIZE = 10;
    private static final int MAX_RUN_PAGE_SIZE = 100;
    private static final int MAX_ISSUE_EXAMPLES = 5;
    private static final String PARSE_FAILED_DOCUMENT_MESSAGE = "解析失败，请检查文件是否损坏或是否包含可读取文本。";
    private static final String PDF_OCR_REQUIRED_DOCUMENT_MESSAGE =
            "该 PDF 没有可抽取文本层；配置 OCR 后可重新解析目录，或先用外部工具生成文本层后同步目录。";
    private static final Pattern VERSION_FAMILY_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\b(v\\d+(?:\\.\\d+)*|final|draft)\\b"
    );
    private static final Pattern VERSION_FAMILY_DATE_PATTERN = Pattern.compile("\\b\\d{4}(?:\\d{2})?(?:\\d{2})?\\b");
    private static final Pattern VERSION_FAMILY_MARKER_PATTERN = Pattern.compile(
            "(?i)(\\bv\\d+(?:\\.\\d+)*\\b|\\bfinal\\b|\\bdraft\\b|最新版|新版|旧版|修订版|终稿|草稿|\\b\\d{4}(?:[-_.]?\\d{2})?(?:[-_.]?\\d{2})?\\b)"
    );
    private static final Pattern VERSION_FAMILY_SUFFIX_PATTERN = Pattern.compile("(最新版|新版|旧版|修订版|终稿|草稿)$");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Set<String> GENERIC_VERSION_FAMILY_NAMES = Set.of(
            "readme",
            "index",
            "toc",
            "catalog",
            "summary",
            "overview",
            "contents",
            "目录",
            "内容",
            "导航",
            "说明",
            "简介"
    );

    private final KnowledgeFolderRepository folderRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeFolderRunRepository runRepository;
    private final KnowledgeGraphRepository graphRepository;
    private final KnowledgeStore knowledgeStore;
    private final DocumentIngestionService ingestionService;
    private final DocumentFailureCodec failureCodec;

    /**
     * 注入知识库健康诊断依赖。
     *
     * @param folderRepository 知识库目录仓储
     * @param documentRepository 文档仓储
     * @param runRepository 维护运行记录仓储
     * @param graphRepository 图谱派生数据仓储
     * @param knowledgeStore 检索索引边界
     * @param ingestionService 文档扫描边界
     * @param failureCodec 失败诊断编解码器
     */
    public KnowledgeHealthService(
            KnowledgeFolderRepository folderRepository,
            DocumentRepository documentRepository,
            KnowledgeFolderRunRepository runRepository,
            KnowledgeGraphRepository graphRepository,
            KnowledgeStore knowledgeStore,
            DocumentIngestionService ingestionService,
            DocumentFailureCodec failureCodec
    ) {
        this.folderRepository = folderRepository;
        this.documentRepository = documentRepository;
        this.runRepository = runRepository;
        this.graphRepository = graphRepository;
        this.knowledgeStore = knowledgeStore;
        this.ingestionService = ingestionService;
        this.failureCodec = failureCodec;
    }

    /**
     * 计算全库健康快照。
     *
     * @return 全库健康响应
     */
    @Transactional(readOnly = true)
    public KnowledgeHealthResponse health() {
        List<KnowledgeFolderSummary> summaries = folderRepository.findAllSummaries();
        Map<String, KnowledgeFolderRun> latestRuns = latestRunsByScope();
        List<KnowledgeFolderRunResponse> currentRuns = runRepository.findActiveRuns().stream()
                .map(KnowledgeFolderRunResponse::from)
                .toList();
        List<KnowledgeFolderRunResponse> queuedRuns = withQueuePositions(runRepository.findQueuedRuns());
        List<FolderHealthSnapshot> folderSnapshots = summaries.stream()
                .map(summary -> folderSnapshot(summary, latestRuns.get(scopeKey(summary.folder().id()))))
                .toList();

        IndexHealthSnapshot indexHealth = indexHealth(folderSnapshots);
        ContentQualitySnapshot contentQuality = contentQuality(folderSnapshots);
        GraphFreshnessSnapshot graphFreshness = graphFreshness(folderSnapshots);
        KnowledgeHealthSummaryResponse summary = totalSummary(
                folderSnapshots,
                indexHealth,
                contentQuality,
                graphFreshness,
                currentRuns.size(),
                queuedRuns.size()
        );
        List<KnowledgeHealthIssueResponse> issues = allIssues(
                folderSnapshots,
                summary,
                indexHealth,
                contentQuality,
                graphFreshness
        );
        KnowledgeHealthStatus status = overallStatus(folderSnapshots, summary, issues);
        List<KnowledgeFolderHealthSummaryResponse> folders = folderSnapshots.stream()
                .map(FolderHealthSnapshot::toSummaryResponse)
                .toList();
        KnowledgeFolderRunResponse latestRun = runRepository.findLatestRun()
                .map(KnowledgeFolderRunResponse::from)
                .orElse(null);

        return new KnowledgeHealthResponse(status, summary, issues, folders, currentRuns, queuedRuns, latestRun);
    }

    /**
     * 计算单个目录健康详情。
     *
     * @param folderId 知识库目录 ID
     * @return 目录健康响应
     */
    @Transactional(readOnly = true)
    public KnowledgeFolderHealthResponse folderHealth(String folderId) {
        KnowledgeFolderSummary summary = folderRepository.findAllSummaries().stream()
                .filter(candidate -> candidate.folder().id().equals(folderId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge folder not found: " + folderId));

        if (!summary.folder().enabled()) {
            return disabledFolderHealth(folderId);
        }

        List<KnowledgeDocument> documents = documentRepository.findByKnowledgeFolderIdOrderByUpdatedAtDesc(folderId);
        FolderHealthProbe probe = probeDocuments(summary.folder(), documents);
        List<KnowledgeHealthIssueResponse> issues = folderIssues(summary, probe, documents);
        KnowledgeHealthStatus status = folderStatus(summary, probe, issues);
        List<KnowledgeFolderRunResponse> runs = runRepository.findRuns(
                        KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                        folderId,
                        DETAIL_RUN_LIMIT
                )
                .stream()
                .map(KnowledgeFolderRunResponse::from)
                .toList();

        return new KnowledgeFolderHealthResponse(
                folderId,
                status,
                issues,
                failedProblemDocuments(documents),
                unindexedDocuments(documents),
                probe.missingLocalFiles(),
                probe.staleLocalFiles(),
                probe.newLocalFiles(),
                runs
        );
    }

    /**
     * 返回停用目录的状态快照。
     *
     * <p>停用是用户主动把目录排除出检索范围，不代表知识库需要修复；因此不继续展开解析、索引和
     * 本地文件探针问题，避免旧文档状态污染全库健康判断。</p>
     *
     * @param folderId 知识库目录 ID
     * @return 停用目录健康响应
     */
    private KnowledgeFolderHealthResponse disabledFolderHealth(String folderId) {
        List<KnowledgeFolderRunResponse> runs = runRepository.findRuns(
                        KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                        folderId,
                        DETAIL_RUN_LIMIT
                )
                .stream()
                .map(KnowledgeFolderRunResponse::from)
                .toList();

        return new KnowledgeFolderHealthResponse(
                folderId,
                KnowledgeHealthStatus.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                runs
        );
    }

    /**
     * 查询维护运行记录。
     *
     * @param scopeType 范围类型；为空时查询全部
     * @param scopeId 范围 ID；全库为空
     * @param limit 最大返回数量
     * @return 运行记录响应
     */
    @Transactional(readOnly = true)
    public List<KnowledgeFolderRunResponse> runs(KnowledgeFolderRunScopeType scopeType, String scopeId, Integer limit) {
        return runRepository.findRuns(scopeType, scopeId, limit)
                .stream()
                .map(KnowledgeFolderRunResponse::from)
                .toList();
    }

    /**
     * 分页查询维护运行记录。
     *
     * @param scopeType 范围类型；为空时查询全部
     * @param scopeId 范围 ID；全库为空
     * @param operations 操作类型过滤；为空时不限制
     * @param statuses 状态过滤；为空时不限制
     * @param keyword 模糊关键词，匹配任务 ID、目录名、目录路径、当前项和错误信息
     * @param timeFrom 起始时间戳；为空时不限制
     * @param timeTo 结束时间戳；为空时不限制
     * @param page 页码，从 1 开始
     * @param pageSize 每页数量
     * @return 分页运行记录响应
     */
    @Transactional(readOnly = true)
    public KnowledgeFolderRunPageResponse runsPage(
            KnowledgeFolderRunScopeType scopeType,
            String scopeId,
            List<KnowledgeFolderRunOperation> operations,
            List<KnowledgeFolderRunStatus> statuses,
            String keyword,
            Long timeFrom,
            Long timeTo,
            Integer page,
            Integer pageSize
    ) {
        int normalizedPage = normalizePage(page);
        int normalizedPageSize = normalizeRunPageSize(pageSize);
        List<KnowledgeFolderRunResponse> items = runRepository.findRunsPage(
                        scopeType,
                        scopeId,
                        operations,
                        statuses,
                        keyword,
                        timeFrom,
                        timeTo,
                        normalizedPage,
                        normalizedPageSize
                )
                .stream()
                .map(KnowledgeFolderRunResponse::from)
                .toList();
        return new KnowledgeFolderRunPageResponse(
                items,
                runRepository.countRuns(scopeType, scopeId, operations, statuses, keyword, timeFrom, timeTo),
                normalizedPage,
                normalizedPageSize
        );
    }

    /**
     * 查询单条维护记录详情。
     *
     * <p>失败明细只在详情接口解析，避免分页列表携带过大的 failures_json。</p>
     *
     * @param runId 运行记录 ID
     * @return 维护记录详情
     */
    @Transactional(readOnly = true)
    public KnowledgeFolderRunDetailResponse runDetail(String runId) {
        KnowledgeFolderRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge maintenance run not found: " + runId));
        KnowledgeFolder folder = run.scopeType() == KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER && run.scopeId() != null
                ? folderRepository.findById(run.scopeId()).orElse(null)
                : null;
        return KnowledgeFolderRunDetailResponse.from(run, folder, parseFailures(run));
    }

    /**
     * 删除单条终态维护历史。
     *
     * @param runId 运行记录 ID
     * @return 删除结果
     */
    @Transactional
    public KnowledgeFolderRunDeleteResponse deleteRun(String runId) {
        KnowledgeFolderRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge maintenance run not found: " + runId));
        if (!isTerminalRun(run.status())) {
            return new KnowledgeFolderRunDeleteResponse(0, 1, List.of(runId));
        }
        boolean deleted = runRepository.deleteTerminalById(runId);
        return new KnowledgeFolderRunDeleteResponse(deleted ? 1 : 0, deleted ? 0 : 1, deleted ? List.of() : List.of(runId));
    }

    /**
     * 批量删除终态维护历史。
     *
     * <p>活跃任务会被跳过而不是强删，保证维护队列事实源不被历史清理入口破坏。</p>
     *
     * @param runIds 运行记录 ID 列表
     * @return 删除结果
     */
    @Transactional
    public KnowledgeFolderRunDeleteResponse deleteRuns(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return new KnowledgeFolderRunDeleteResponse(0, 0, List.of());
        }
        List<String> normalizedIds = runIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .distinct()
                .toList();
        List<String> deletableIds = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        for (String id : normalizedIds) {
            runRepository.findById(id)
                    .ifPresentOrElse(run -> {
                        if (isTerminalRun(run.status())) {
                            deletableIds.add(id);
                        } else {
                            skippedIds.add(id);
                        }
                    }, () -> skippedIds.add(id));
        }
        int deletedCount = runRepository.deleteTerminalByIds(deletableIds);
        if (deletedCount < deletableIds.size()) {
            skippedIds.addAll(deletableIds.subList(deletedCount, deletableIds.size()));
        }
        return new KnowledgeFolderRunDeleteResponse(deletedCount, skippedIds.size(), skippedIds);
    }

    /**
     * 读取每个目录最近一次维护记录。
     *
     * <p>全库运行记录不直接挂到目录卡片上；目录卡片只展示自身 scope 的最近维护结果。</p>
     *
     * @return 以 scopeKey 为键的最近运行记录
     */
    private Map<String, KnowledgeFolderRun> latestRunsByScope() {
        Map<String, KnowledgeFolderRun> runs = new LinkedHashMap<>();
        for (KnowledgeFolderRun run : runRepository.findLatestRunsByScope()) {
            if (run.scopeType() == KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER && run.scopeId() != null) {
                runs.put(scopeKey(run.scopeId()), run);
            }
        }
        return runs;
    }

    private List<DocumentFailureResponse> parseFailures(KnowledgeFolderRun run) {
        return failureCodec.decodeFailures(run.failuresJson());
    }

    private static boolean isTerminalRun(KnowledgeFolderRunStatus status) {
        return status != KnowledgeFolderRunStatus.QUEUED
                && status != KnowledgeFolderRunStatus.RUNNING
                && status != KnowledgeFolderRunStatus.CANCELLING;
    }

    /**
     * 计算目录在全库列表中的轻量健康快照。
     *
     * <p>快照只保留计数和最近运行记录，不携带完整问题文档列表，避免全库健康接口返回过大的响应。</p>
     *
     * @param summary 目录统计摘要
     * @param latestRun 目录最近一次维护记录；没有记录时为空
     * @return 目录健康快照
     */
    private FolderHealthSnapshot folderSnapshot(KnowledgeFolderSummary summary, KnowledgeFolderRun latestRun) {
        if (!summary.folder().enabled()) {
            return new FolderHealthSnapshot(
                    summary,
                    KnowledgeHealthStatus.DISABLED,
                    0,
                    0,
                    0,
                    0,
                    latestRun == null ? null : KnowledgeFolderRunResponse.from(latestRun),
                    List.of(),
                    List.of()
            );
        }

        List<KnowledgeDocument> documents = documentRepository.findByKnowledgeFolderIdOrderByUpdatedAtDesc(
                summary.folder().id()
        );
        FolderHealthProbe probe = probeDocuments(summary.folder(), documents);
        List<KnowledgeHealthIssueResponse> issues = folderIssues(summary, probe, documents);
        return new FolderHealthSnapshot(
                summary,
                folderStatus(summary, probe, issues),
                probe.missingLocalFiles().size(),
                probe.staleLocalFiles().size(),
                probe.newLocalFiles().size(),
                indexedChunkCount(documents),
                latestRun == null ? null : KnowledgeFolderRunResponse.from(latestRun),
                issues,
                documents
        );
    }

    /**
     * 探测目录本地文件与 SQLite 文档记录之间的差异。
     *
     * <p>健康诊断是只读、尽力而为的入口。单个文档路径损坏、权限异常或外置盘不可用时，
     * 应把该文档报告为不可访问，而不是让整个健康面板返回 500。</p>
     *
     * @param folder 知识库目录配置
     * @param documents 目录下的文档记录
     * @return 文件缺失、疑似变化和本地新增的探针结果
     */
    private FolderHealthProbe probeDocuments(KnowledgeFolder folder, List<KnowledgeDocument> documents) {
        List<KnowledgeProblemDocumentResponse> missingLocalFiles = new ArrayList<>();
        List<KnowledgeProblemDocumentResponse> staleLocalFiles = new ArrayList<>();
        Map<String, KnowledgeDocument> documentsById = new LinkedHashMap<>();
        for (KnowledgeDocument document : documents) {
            documentsById.put(document.id(), document);
            try {
                Path path = Path.of(document.sourcePath());
                if (!Files.exists(path) || !Files.isRegularFile(path)) {
                    missingLocalFiles.add(KnowledgeProblemDocumentResponse.from(document, "本地文件不存在，点击同步可清理应用内记录。"));
                    continue;
                }
                if (isStale(document, path)) {
                    staleLocalFiles.add(KnowledgeProblemDocumentResponse.from(document, "本地文件大小或修改时间已变化，建议同步目录。"));
                }
            } catch (RuntimeException ex) {
                log.warn("knowledge_health_file_probe_failed documentId={} path={}",
                        document.id(),
                        document.sourcePath(),
                        ex
                );
                missingLocalFiles.add(KnowledgeProblemDocumentResponse.from(document, probeFailureMessage(ex)));
            }
        }
        List<KnowledgeProblemDocumentResponse> newLocalFiles = newLocalFiles(folder, documentsById);
        return new FolderHealthProbe(
                List.copyOf(missingLocalFiles),
                List.copyOf(staleLocalFiles),
                List.copyOf(newLocalFiles)
        );
    }

    /**
     * 找出本地目录中尚未进入 SQLite 的受支持文件。
     *
     * <p>这里复用导入服务的扫描和 ID 规则，避免健康诊断与同步动作看到两套不同的文件集合。</p>
     *
     * @param folder 知识库目录配置
     * @param documentsById SQLite 已记录文档
     * @return 本地新增文件列表
     */
    private List<KnowledgeProblemDocumentResponse> newLocalFiles(
            KnowledgeFolder folder,
            Map<String, KnowledgeDocument> documentsById
    ) {
        try {
            return ingestionService.scanDocumentFiles(folder.folderPath(), folder.recursive())
                    .stream()
                    .filter(file -> !documentsById.containsKey(file.documentId()))
                    .map(file -> KnowledgeProblemDocumentResponse.from(file, "本地目录中有新增文件，点击同步可纳入知识库。"))
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("knowledge_health_new_file_probe_failed folderId={} path={}",
                    folder.id(),
                    folder.folderPath(),
                    ex
            );
            return List.of();
        }
    }

    /**
     * 判断本地文件是否与上次解析记录不一致。
     *
     * <p>仅比较大小和修改时间，避免在健康查询里重新读取全文或计算 hash；需要精确收敛时由同步流程处理。</p>
     *
     * @param document SQLite 中保存的文档元数据
     * @param path 当前本地文件路径
     * @return 是否疑似变化
     */
    private boolean isStale(KnowledgeDocument document, Path path) {
        try {
            return Files.size(path) != document.fileSize()
                    || Files.getLastModifiedTime(path).toMillis() != document.lastModified();
        } catch (IOException ex) {
            // 无法读取文件元数据时按“疑似变化”处理，让用户通过同步重新收敛 SQLite 记录。
            return true;
        }
    }

    /**
     * 将文件探针异常转换为用户可读的问题说明。
     *
     * @param ex 路径解析或文件系统访问异常
     * @return 问题文档展示消息
     */
    private static String probeFailureMessage(RuntimeException ex) {
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            return "无法访问本地文件，请检查路径格式或文件权限。";
        }
        return "无法访问本地文件：" + detail;
    }

    /**
     * 根据目录统计和文件探针结果生成目录级问题列表。
     *
     * <p>每个问题都携带建议动作，前端只展示或映射到用户显式操作，不由健康查询自动修复。</p>
     *
     * @param summary 目录统计摘要
     * @param probe 文件系统探针结果
     * @return 目录级健康问题
     */
    private List<KnowledgeHealthIssueResponse> folderIssues(
            KnowledgeFolderSummary summary,
            FolderHealthProbe probe,
            List<KnowledgeDocument> documents
    ) {
        List<KnowledgeHealthIssueResponse> issues = new ArrayList<>();
        KnowledgeFolder folder = summary.folder();
        String folderId = folder.id();
        int processingFailureCount = processingFailureCount(documents);
        int pdfOcrRequiredCount = documentStatusCount(documents, DocumentStatus.OCR_REQUIRED);
        if (!folder.enabled()) {
            return List.of();
        }
        if (!Files.isDirectory(Path.of(folder.folderPath()))) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.FOLDER_NOT_FOUND,
                    "ERROR",
                    "本地目录当前不可访问，目录内资料无法确认是否最新。",
                    "DELETE_FOLDER",
                    folderId,
                    1
            ));
        }
        if (folder.enabled() && summary.documentCount() == 0 && probe.newLocalFiles().isEmpty()) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.NO_DOCUMENTS,
                    "WARNING",
                    "目录中还没有文档记录。",
                    "SYNC_FOLDER",
                    folderId,
                    1
            ));
        }
        if (processingFailureCount > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.DOCUMENT_PROCESSING_FAILED,
                    "WARNING",
                    "有 " + processingFailureCount + " 个文档最近处理失败，请查看具体失败阶段。",
                    "SYNC_FOLDER",
                    folderId,
                    processingFailureCount
            ));
        }
        if (pdfOcrRequiredCount > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.PDF_OCR_REQUIRED,
                    "WARNING",
                    "有 " + pdfOcrRequiredCount + " 个 PDF 没有可抽取文本层，需要 OCR 后才能进入知识库。",
                    "REPARSE_FOLDER",
                    folderId,
                    pdfOcrRequiredCount
            ));
        }
        if (summary.unindexedCount() > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.UNINDEXED_DOCUMENTS,
                    "ERROR",
                    "有 " + summary.unindexedCount() + " 个已解析文档尚未进入索引。",
                    "REPAIR_INDEX",
                    folderId,
                    summary.unindexedCount()
            ));
        }
        if (!probe.missingLocalFiles().isEmpty()) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.MISSING_LOCAL_FILES,
                    "WARNING",
                    "有 " + probe.missingLocalFiles().size() + " 个已记录文件在本地不存在。",
                    "SYNC_FOLDER",
                    folderId,
                    probe.missingLocalFiles().size()
            ));
        }
        if (!probe.staleLocalFiles().isEmpty()) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.STALE_LOCAL_FILES,
                    "WARNING",
                    "有 " + probe.staleLocalFiles().size() + " 个本地文件疑似已变化。",
                    "SYNC_FOLDER",
                    folderId,
                    probe.staleLocalFiles().size()
            ));
        }
        if (!probe.newLocalFiles().isEmpty()) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.NEW_LOCAL_FILES,
                    "WARNING",
                    "有 " + probe.newLocalFiles().size() + " 个本地新增文件尚未同步到知识库。",
                    "SYNC_FOLDER",
                    folderId,
                    probe.newLocalFiles().size()
            ));
        }
        return List.copyOf(issues);
    }

    /**
     * 计算单个目录的最终健康状态。
     *
     * <p>DISABLED 是用户主动排除目录的状态，不作为健康问题升级；ERROR 表示需要修复才能恢复可信检索；
     * EMPTY 只在启用且没有文档时出现。</p>
     *
     * @param summary 目录统计摘要
     * @param probe 文件系统探针结果
     * @param issues 已生成的问题列表
     * @return 目录健康状态
     */
    private KnowledgeHealthStatus folderStatus(
            KnowledgeFolderSummary summary,
            FolderHealthProbe probe,
            List<KnowledgeHealthIssueResponse> issues
    ) {
        if (!summary.folder().enabled()) {
            return KnowledgeHealthStatus.DISABLED;
        }
        if (hasSeverity(issues, "ERROR")) {
            return KnowledgeHealthStatus.ERROR;
        }
        if (hasSeverity(issues, "WARNING")
                || !probe.missingLocalFiles().isEmpty()
                || !probe.staleLocalFiles().isEmpty()
                || !probe.newLocalFiles().isEmpty()) {
            return KnowledgeHealthStatus.WARNING;
        }
        if (summary.documentCount() == 0) {
            return KnowledgeHealthStatus.EMPTY;
        }
        return KnowledgeHealthStatus.HEALTHY;
    }

    /**
     * 汇总全库健康统计。
     *
     * <p>lastIngestedAt 和 lastIndexedAt 使用所有目录中的最大值，表达“全库最近一次发生过的维护时间”。</p>
     *
     * @param snapshots 目录健康快照列表
     * @return 全库统计摘要
     */
    private KnowledgeHealthSummaryResponse totalSummary(
            List<FolderHealthSnapshot> snapshots,
            IndexHealthSnapshot indexHealth,
            ContentQualitySnapshot contentQuality,
            GraphFreshnessSnapshot graphFreshness,
            int runningRunCount,
            int queuedRunCount
    ) {
        int folderCount = snapshots.size();
        int enabledFolderCount = 0;
        int documentCount = 0;
        int parsedCount = 0;
        int failedCount = 0;
        int unindexedCount = 0;
        int missingLocalFileCount = 0;
        int staleLocalFileCount = 0;
        int newLocalFileCount = 0;
        int chunkCount = 0;
        Long lastIngestedAt = null;
        Long lastIndexedAt = null;

        for (FolderHealthSnapshot snapshot : snapshots) {
            KnowledgeFolderSummary summary = snapshot.summary();
            if (!summary.folder().enabled()) {
                lastIngestedAt = maxNullable(lastIngestedAt, summary.folder().lastIngestedAt());
                lastIndexedAt = maxNullable(lastIndexedAt, summary.folder().lastIndexedAt());
                continue;
            }

            enabledFolderCount++;
            documentCount += summary.documentCount();
            parsedCount += summary.parsedCount();
            failedCount += summary.failedCount();
            unindexedCount += summary.unindexedCount();
            missingLocalFileCount += snapshot.missingLocalFileCount();
            staleLocalFileCount += snapshot.staleLocalFileCount();
            newLocalFileCount += snapshot.newLocalFileCount();
            chunkCount += summary.chunkCount();
            lastIngestedAt = maxNullable(lastIngestedAt, summary.folder().lastIngestedAt());
            lastIndexedAt = maxNullable(lastIndexedAt, summary.folder().lastIndexedAt());
        }

        int searchableDocumentCount = Math.max(0, parsedCount - unindexedCount);
        int syncIssueCount = missingLocalFileCount + staleLocalFileCount + newLocalFileCount;
        int retrievalIssueCount = failedCount
                + unindexedCount
                + (indexHealth.indexConsistent() ? 0 : 1)
                + (embeddingConfiguredIssue(documentCount, indexHealth) ? 1 : 0);
        int conflictIssueCount = contentQuality.duplicateDocumentCount()
                + contentQuality.versionConflictGroupCount();
        boolean answerReady = documentCount > 0
                && searchableDocumentCount > 0
                && syncIssueCount == 0
                && failedCount == 0
                && unindexedCount == 0
                && indexHealth.indexConsistent();

        return new KnowledgeHealthSummaryResponse(
                folderCount,
                enabledFolderCount,
                documentCount,
                parsedCount,
                failedCount,
                unindexedCount,
                missingLocalFileCount,
                staleLocalFileCount,
                newLocalFileCount,
                chunkCount,
                lastIngestedAt,
                lastIndexedAt,
                indexHealth.luceneDocumentCount(),
                indexHealth.luceneChunkCount(),
                indexHealth.embeddingConfigured(),
                indexHealth.indexConsistent(),
                runningRunCount,
                queuedRunCount,
                answerReady,
                searchableDocumentCount,
                syncIssueCount,
                retrievalIssueCount,
                conflictIssueCount,
                graphFreshness.staleScopeCount()
        );
    }

    private static List<KnowledgeFolderRunResponse> withQueuePositions(List<KnowledgeFolderRun> runs) {
        List<KnowledgeFolderRunResponse> responses = new ArrayList<>();
        int position = 1;
        for (KnowledgeFolderRun run : runs) {
            responses.add(KnowledgeFolderRunResponse.from(run).withQueuePosition(
                    run.status() == KnowledgeFolderRunStatus.QUEUED ? position++ : null
            ));
        }
        return List.copyOf(responses);
    }

    /**
     * 读取 Lucene 和 Embedding 状态并与 SQLite 快照做一致性判断。
     *
     * <p>SQLite 仍是业务事实来源；Lucene 统计只用于发现索引目录损坏或漏写。</p>
     *
     * @param snapshots 当前目录健康快照
     * @return 索引健康快照
     */
    private IndexHealthSnapshot indexHealth(List<FolderHealthSnapshot> snapshots) {
        long expectedIndexedDocumentCount = 0;
        long expectedIndexedChunkCount = 0;

        for (FolderHealthSnapshot snapshot : snapshots) {
            if (!snapshot.summary().folder().enabled()) {
                continue;
            }
            expectedIndexedDocumentCount += Math.max(0, snapshot.summary().parsedCount() - snapshot.summary().unindexedCount());
            expectedIndexedChunkCount += snapshot.indexedChunkCount();
        }
        List<KnowledgeDocument> unassignedDocuments = documentRepository.findUnassignedOrderByUpdatedAtDesc();
        expectedIndexedDocumentCount += unassignedDocuments.stream()
                .filter(document -> document.status() == DocumentStatus.PARSED)
                .filter(document -> document.indexedAt() != null)
                .count();
        expectedIndexedChunkCount += indexedChunkCount(unassignedDocuments);

        try {
            IndexStatusResponse status = knowledgeStore.status();
            boolean consistent = status.indexedDocumentCount() == expectedIndexedDocumentCount
                    && status.indexedChunkCount() == expectedIndexedChunkCount;
            return new IndexHealthSnapshot(
                    status.indexedDocumentCount(),
                    status.indexedChunkCount(),
                    status.embeddingConfigured(),
                    consistent,
                    true,
                    null
            );
        } catch (RuntimeException ex) {
            log.warn("knowledge_health_index_probe_failed", ex);
            return new IndexHealthSnapshot(0, 0, false, false, false, ex.getMessage());
        }
    }

    /**
     * 将目录级问题按问题类型聚合为全库问题。
     *
     * <p>聚合后 scopeType 变为 ALL，scopeId 为空；具体目录仍可通过 folders 或目录详情查看。</p>
     *
     * @param snapshots 目录健康快照列表
     * @return 全库聚合问题列表
     */
    private List<KnowledgeHealthIssueResponse> allIssues(
            List<FolderHealthSnapshot> snapshots,
            KnowledgeHealthSummaryResponse summary,
            IndexHealthSnapshot indexHealth,
            ContentQualitySnapshot contentQuality,
            GraphFreshnessSnapshot graphFreshness
    ) {
        List<KnowledgeHealthIssueResponse> issues = new ArrayList<>();
        issues.addAll(aggregateIssues(snapshots));
        issues.addAll(systemIssues(summary, indexHealth, contentQuality, graphFreshness));
        return issues.stream()
                .sorted(Comparator.comparingInt(issue -> severityRank(issue.severity())))
                .toList();
    }

    /**
     * 生成不属于单个目录的全局诊断问题。
     *
     * @param summary 全库统计摘要
     * @param indexHealth 索引健康快照
     * @return 全局问题列表
     */
    private List<KnowledgeHealthIssueResponse> systemIssues(
            KnowledgeHealthSummaryResponse summary,
            IndexHealthSnapshot indexHealth,
            ContentQualitySnapshot contentQuality,
            GraphFreshnessSnapshot graphFreshness
    ) {
        List<KnowledgeHealthIssueResponse> issues = new ArrayList<>();
        if (!indexHealth.indexConsistent()) {
            String message = indexHealth.readable()
                    ? "Lucene 索引与 SQLite 文档记录不一致，搜索和 RAG 可能缺失内容。"
                    : "Lucene 索引状态读取失败，建议重建索引。";
            issues.add(issue(
                    KnowledgeHealthIssueCode.INDEX_INCONSISTENT,
                    "ERROR",
                    message,
                    "REPAIR_INDEX",
                    null,
                    1
            ));
        }
        if (embeddingConfiguredIssue(summary.documentCount(), indexHealth)) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.EMBEDDING_UNCONFIGURED,
                    "WARNING",
                    "尚未配置可用向量模型；向量或混合检索会降级为关键词检索。",
                    "CONFIGURE_EMBEDDING",
                    null,
                    1
            ));
        }
        if (graphFreshness.staleScopeCount() > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.GRAPH_STALE,
                    "INFO",
                    "有 " + graphFreshness.staleScopeCount() + " 个已生成图谱早于最新资料状态。",
                    "REBUILD_GRAPH",
                    null,
                    graphFreshness.staleScopeCount(),
                    graphFreshness.examples(),
                    graphFreshness.exampleDetails()
            ));
        }
        if (contentQuality.duplicateDocumentCount() > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.DUPLICATE_DOCUMENT_CONTENT,
                    "INFO",
                    "有 " + contentQuality.duplicateDocumentCount() + " 个已解析资料内容重复，可能增加检索噪音。",
                    "VIEW_CONFLICTS",
                    null,
                    contentQuality.duplicateDocumentCount(),
                    contentQuality.duplicateExamples(),
                    contentQuality.duplicateExampleDetails()
            ));
        }
        if (contentQuality.versionConflictGroupCount() > 0) {
            issues.add(issue(
                    KnowledgeHealthIssueCode.POSSIBLE_VERSION_CONFLICT,
                    "WARNING",
                    "有 " + contentQuality.versionConflictGroupCount() + " 组资料疑似存在多个内容版本。",
                    "VIEW_CONFLICTS",
                    null,
                    contentQuality.versionConflictGroupCount(),
                    contentQuality.versionConflictExamples(),
                    contentQuality.versionConflictExampleDetails()
            ));
        }
        return List.copyOf(issues);
    }

    private List<KnowledgeHealthIssueResponse> aggregateIssues(List<FolderHealthSnapshot> snapshots) {
        Map<KnowledgeHealthIssueCode, IssueAggregate> aggregates = new LinkedHashMap<>();
        for (FolderHealthSnapshot snapshot : snapshots) {
            for (KnowledgeHealthIssueResponse issue : snapshot.issues()) {
                aggregates.computeIfAbsent(issue.code(), ignored -> new IssueAggregate(issue))
                        .add(issue);
            }
        }
        return aggregates.values().stream()
                .map(IssueAggregate::toResponse)
                .sorted(Comparator.comparingInt(issue -> severityRank(issue.severity())))
                .toList();
    }

    /**
     * 诊断会影响问答命中质量的资料重复和版本冲突。
     *
     * <p>这里不写入“可信标签”，也不自动合并或删除资料；重复和版本判断只是从当前 SQLite 事实即时派生的
     * 问答噪音信号，最终是否保留多个版本必须由用户决定。该诊断会对启用目录下已解析文档做 O(n)
     * 轻量扫描；如果后续本地知识库进入万级文档规模，再考虑下推到 SQL 聚合或增加短期缓存。</p>
     *
     * @param snapshots 启用目录健康快照
     * @return 内容质量诊断快照
     */
    private ContentQualitySnapshot contentQuality(List<FolderHealthSnapshot> snapshots) {
        Map<String, List<KnowledgeDocument>> documentsByHash = new LinkedHashMap<>();
        Map<VersionConflictGroupKey, List<KnowledgeDocument>> documentsByVersionFamily = new LinkedHashMap<>();
        for (KnowledgeDocument document : parsedEnabledDocuments(snapshots)) {
            if (document.contentHash() != null && !document.contentHash().isBlank()) {
                documentsByHash.computeIfAbsent(document.contentHash(), ignored -> new ArrayList<>()).add(document);
            }
            VersionConflictGroupKey versionGroupKey = versionConflictGroupKey(document);
            if (versionGroupKey != null) {
                documentsByVersionFamily.computeIfAbsent(versionGroupKey, ignored -> new ArrayList<>()).add(document);
            }
        }

        int duplicateDocumentCount = 0;
        List<String> duplicateExamples = new ArrayList<>();
        List<KnowledgeHealthIssueExampleResponse> duplicateExampleDetails = new ArrayList<>();
        for (List<KnowledgeDocument> documents : documentsByHash.values()) {
            if (documents.size() <= 1) {
                continue;
            }
            duplicateDocumentCount += documents.size();
            addExample(duplicateExamples, exampleFileList("重复内容", documents));
            addExampleDetail(
                    duplicateExampleDetails,
                    new KnowledgeHealthIssueExampleResponse(
                            "DOCUMENT_GROUP",
                            "重复内容",
                            "这些资料内容完全相同，检索时可能让同一来源反复出现。",
                            null,
                            null,
                            exampleDocumentItems(documents)
                    )
            );
        }

        int versionConflictGroupCount = 0;
        List<String> versionConflictExamples = new ArrayList<>();
        List<KnowledgeHealthIssueExampleResponse> versionConflictExampleDetails = new ArrayList<>();
        for (Map.Entry<VersionConflictGroupKey, List<KnowledgeDocument>> entry : documentsByVersionFamily.entrySet()) {
            List<KnowledgeDocument> documents = entry.getValue();
            if (documents.size() <= 1 || distinctContentHashCount(documents) <= 1) {
                continue;
            }
            versionConflictGroupCount++;
            addExample(versionConflictExamples, exampleFileList(entry.getKey().label(), documents));
            addExampleDetail(
                    versionConflictExampleDetails,
                    new KnowledgeHealthIssueExampleResponse(
                            "DOCUMENT_GROUP",
                            "疑似版本冲突：" + entry.getKey().label(),
                            "这些资料命中了版本冲突规则，回答时可能混用旧版和新版。",
                            null,
                            null,
                            exampleDocumentItems(documents)
                    )
            );
        }

        return new ContentQualitySnapshot(
                duplicateDocumentCount,
                versionConflictGroupCount,
                duplicateExamples,
                versionConflictExamples,
                duplicateExampleDetails,
                versionConflictExampleDetails
        );
    }

    /**
     * 诊断已生成图谱是否落后于当前资料状态。
     *
     * <p>图谱是问答的辅助视图，不是基础检索事实源；因此过期只提示重建，不把知识库直接判为不可问。</p>
     *
     * @param snapshots 启用目录健康快照
     * @return 图谱新鲜度快照
     */
    private GraphFreshnessSnapshot graphFreshness(List<FolderHealthSnapshot> snapshots) {
        Map<String, Long> folderMaterialUpdatedAt = new HashMap<>();
        Map<String, Long> documentMaterialUpdatedAt = new HashMap<>();
        Map<String, String> folderLabelById = new HashMap<>();
        Map<String, String> documentLabelById = new HashMap<>();
        Map<String, String> documentPathById = new HashMap<>();
        Long allMaterialUpdatedAt = null;

        for (FolderHealthSnapshot snapshot : snapshots) {
            if (!snapshot.summary().folder().enabled()) {
                continue;
            }
            String folderId = snapshot.summary().folder().id();
            folderLabelById.put(folderId, readableFolderName(snapshot.summary().folder()));
            Long folderUpdatedAt = maxNullable(
                    snapshot.summary().folder().lastIngestedAt(),
                    snapshot.summary().folder().lastIndexedAt()
            );
            for (KnowledgeDocument document : snapshot.documents()) {
                Long documentUpdatedAt = maxNullable(document.updatedAt(), document.indexedAt());
                documentMaterialUpdatedAt.put(document.id(), documentUpdatedAt);
                documentLabelById.put(document.id(), readableDocumentName(document));
                documentPathById.put(document.id(), document.sourcePath());
                folderUpdatedAt = maxNullable(folderUpdatedAt, documentUpdatedAt);
            }
            folderMaterialUpdatedAt.put(folderId, folderUpdatedAt);
            allMaterialUpdatedAt = maxNullable(allMaterialUpdatedAt, folderUpdatedAt);
        }

        int staleScopeCount = 0;
        List<String> examples = new ArrayList<>();
        List<KnowledgeHealthIssueExampleResponse> exampleDetails = new ArrayList<>();
        for (KnowledgeGraphSummaryRow row : graphRepository.findGeneratedGraphSummaries()) {
            Long materialUpdatedAt = graphMaterialUpdatedAt(row, allMaterialUpdatedAt, folderMaterialUpdatedAt, documentMaterialUpdatedAt);
            if (materialUpdatedAt == null || row.generatedAt() == null || row.generatedAt() >= materialUpdatedAt) {
                continue;
            }
            staleScopeCount++;
            KnowledgeHealthIssueExampleResponse detail = graphScopeExample(row, folderLabelById, documentLabelById, documentPathById);
            addExample(examples, detail.label());
            addExampleDetail(exampleDetails, detail);
        }
        return new GraphFreshnessSnapshot(staleScopeCount, examples, exampleDetails);
    }

    private static List<KnowledgeDocument> parsedEnabledDocuments(List<FolderHealthSnapshot> snapshots) {
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (FolderHealthSnapshot snapshot : snapshots) {
            if (!snapshot.summary().folder().enabled()) {
                continue;
            }
            snapshot.documents().stream()
                    .filter(document -> document.status() == DocumentStatus.PARSED)
                    .forEach(documents::add);
        }
        return documents;
    }

    private static int distinctContentHashCount(List<KnowledgeDocument> documents) {
        Set<String> hashes = new HashSet<>();
        for (KnowledgeDocument document : documents) {
            if (document.contentHash() != null && !document.contentHash().isBlank()) {
                hashes.add(document.contentHash());
            }
        }
        return hashes.size();
    }

    private static void addExample(List<String> examples, String example) {
        if (examples.size() < MAX_ISSUE_EXAMPLES && example != null && !example.isBlank()) {
            examples.add(example);
        }
    }

    private static void addExampleDetail(
            List<KnowledgeHealthIssueExampleResponse> examples,
            KnowledgeHealthIssueExampleResponse example
    ) {
        if (examples.size() < MAX_ISSUE_EXAMPLES && example != null && example.label() != null && !example.label().isBlank()) {
            examples.add(example);
        }
    }

    private static String exampleFileList(String label, List<KnowledgeDocument> documents) {
        String files = documents.stream()
                .limit(3)
                .map(KnowledgeDocument::fileName)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + " / " + right)
                .orElse("未命名资料");
        return label + "：" + files;
    }

    private static List<String> exampleDocumentItems(List<KnowledgeDocument> documents) {
        return documents.stream()
                .limit(5)
                .map(KnowledgeHealthService::readableDocumentItem)
                .filter(Objects::nonNull)
                .toList();
    }

    private static String readableDocumentItem(KnowledgeDocument document) {
        String name = readableDocumentName(document);
        if (document.sourcePath() == null || document.sourcePath().isBlank() || Objects.equals(name, document.sourcePath())) {
            return name;
        }
        return name + " · " + document.sourcePath();
    }

    private static Long graphMaterialUpdatedAt(
            KnowledgeGraphSummaryRow row,
            Long allMaterialUpdatedAt,
            Map<String, Long> folderMaterialUpdatedAt,
            Map<String, Long> documentMaterialUpdatedAt
    ) {
        return switch (row.scopeType()) {
            case "ALL" -> allMaterialUpdatedAt;
            case "KNOWLEDGE_FOLDER" -> folderMaterialUpdatedAt.get(row.scopeId());
            case "DOCUMENT" -> documentMaterialUpdatedAt.get(row.scopeId());
            default -> null;
        };
    }

    private static KnowledgeHealthIssueExampleResponse graphScopeExample(
            KnowledgeGraphSummaryRow row,
            Map<String, String> folderLabelById,
            Map<String, String> documentLabelById,
            Map<String, String> documentPathById
    ) {
        if ("ALL".equals(row.scopeType())) {
            return new KnowledgeHealthIssueExampleResponse(
                    "GRAPH_SCOPE",
                    "全库图谱",
                    "全库资料已有更新，建议重新生成全库图谱。",
                    "ALL",
                    null,
                    List.of()
            );
        }
        if ("KNOWLEDGE_FOLDER".equals(row.scopeType())) {
            return new KnowledgeHealthIssueExampleResponse(
                    "GRAPH_SCOPE",
                    "目录图谱：" + folderLabelById.getOrDefault(row.scopeId(), "未知目录"),
                    "该目录资料已有更新，建议只重建这个目录图谱。",
                    "KNOWLEDGE_FOLDER",
                    row.scopeId(),
                    List.of()
            );
        }
        if ("DOCUMENT".equals(row.scopeType())) {
            String documentName = documentLabelById.getOrDefault(row.scopeId(), "未知文档");
            String documentPath = documentPathById.get(row.scopeId());
            return new KnowledgeHealthIssueExampleResponse(
                    "GRAPH_SCOPE",
                    "文档图谱：" + documentName,
                    "该文档已有更新，建议只重建这个文档图谱。",
                    "DOCUMENT",
                    row.scopeId(),
                    documentPath == null || documentPath.isBlank() ? List.of() : List.of(documentPath)
            );
        }
        return new KnowledgeHealthIssueExampleResponse(
                "GRAPH_SCOPE",
                row.scopeType() + " 图谱",
                "该图谱范围已有更新，建议进入知识图谱页确认后重建。",
                row.scopeType(),
                row.scopeId(),
                List.of()
        );
    }

    private static String readableFolderName(KnowledgeFolder folder) {
        if (folder.displayName() != null && !folder.displayName().isBlank()) {
            return folder.displayName();
        }
        if (folder.folderPath() != null && !folder.folderPath().isBlank()) {
            return fileNameFromPath(folder.folderPath());
        }
        return folder.id();
    }

    private static String readableDocumentName(KnowledgeDocument document) {
        if (document.fileName() != null && !document.fileName().isBlank()) {
            return document.fileName();
        }
        if (document.sourcePath() != null && !document.sourcePath().isBlank()) {
            return fileNameFromPath(document.sourcePath());
        }
        return document.id();
    }

    private static String fileNameFromPath(String path) {
        try {
            Path fileName = Path.of(path).getFileName();
            return fileName == null ? path : fileName.toString();
        } catch (InvalidPathException ignored) {
            // 诊断接口只能因为路径不可访问降级展示，不能因为历史脏路径导致整个健康页失败。
            return path;
        }
    }

    private static VersionConflictGroupKey versionConflictGroupKey(KnowledgeDocument document) {
        String normalizedName = normalizeVersionFamilyName(document.fileName());
        if (normalizedName.isBlank() || GENERIC_VERSION_FAMILY_NAMES.contains(normalizedName)) {
            return null;
        }

        if (!hasVersionEvidence(document.fileName())) {
            return null;
        }
        return new VersionConflictGroupKey("version:" + normalizedName, normalizedName);
    }

    private static boolean hasVersionEvidence(String fileName) {
        String baseName = stripExtension(fileName);
        return !baseName.isBlank() && VERSION_FAMILY_MARKER_PATTERN.matcher(baseName).find();
    }

    /**
     * 将文件名压缩成“疑似同一资料版本族”的比较键。
     *
     * <p>规则故意保守：调用方必须先确认文件名带有版本证据；这里只去掉扩展名、常见日期/版本后缀和
     * 分隔符，避免把普通同名资料误判成同一资料的旧版/新版。</p>
     *
     * @param fileName 原始文件名
     * @return 版本族比较键；无法判断时为空字符串
     */
    private static String normalizeVersionFamilyName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String baseName = stripExtension(fileName);
        String normalized = baseName
                .toLowerCase(Locale.ROOT)
                .replace('（', ' ')
                .replace('）', ' ')
                .replace('(', ' ')
                .replace(')', ' ')
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('_', ' ')
                .replace('-', ' ')
                .replace('.', ' ');
        normalized = VERSION_FAMILY_TOKEN_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = VERSION_FAMILY_DATE_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = VERSION_FAMILY_SUFFIX_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = MULTI_SPACE_PATTERN.matcher(normalized).replaceAll(" ").strip();
        return normalized.length() < 2 ? "" : normalized;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String baseName = fileName.strip();
        int dotIndex = baseName.lastIndexOf('.');
        return dotIndex > 0 ? baseName.substring(0, dotIndex) : baseName;
    }

    private static boolean embeddingConfiguredIssue(int documentCount, IndexHealthSnapshot indexHealth) {
        return documentCount > 0 && !indexHealth.embeddingConfigured();
    }

    /**
     * 计算全库最终健康状态。
     *
     * <p>全库状态只评价启用目录的可检索语料；停用目录是用户主动排除的范围，不参与 WARNING/ERROR 升级。</p>
     *
     * @param snapshots 目录健康快照列表
     * @param summary 全库统计摘要
     * @param issues 全库聚合问题
     * @return 全库健康状态
     */
    private KnowledgeHealthStatus overallStatus(
            List<FolderHealthSnapshot> snapshots,
            KnowledgeHealthSummaryResponse summary,
            List<KnowledgeHealthIssueResponse> issues
    ) {
        if (summary.folderCount() == 0 || summary.documentCount() == 0) {
            if (hasSeverity(issues, "ERROR")) {
                return KnowledgeHealthStatus.ERROR;
            }
            if (hasSeverity(issues, "WARNING")) {
                return KnowledgeHealthStatus.WARNING;
            }
            return KnowledgeHealthStatus.EMPTY;
        }
        if (hasSeverity(issues, "ERROR")) {
            return KnowledgeHealthStatus.ERROR;
        }
        if (hasSeverity(issues, "WARNING")) {
            return KnowledgeHealthStatus.WARNING;
        }
        return KnowledgeHealthStatus.HEALTHY;
    }

    /**
     * 找出无法进入检索的问题文档。
     *
     * <p>OCR_REQUIRED 与 FAILED 都不会写入 chunk 或索引，但用户处理方式不同，因此详情文案按状态拆分。</p>
     *
     * @param documents 目录下的文档记录
     * @return 失败或需要 OCR 的问题文档列表
     */
    private List<KnowledgeProblemDocumentResponse> failedProblemDocuments(List<KnowledgeDocument> documents) {
        return documents.stream()
                .filter(document -> document.status() == DocumentStatus.FAILED
                        || document.status() == DocumentStatus.OCR_REQUIRED
                        || document.hasLastFailure())
                .map(document -> KnowledgeProblemDocumentResponse.from(
                        document,
                        problemDocumentMessage(document)
                ))
                .toList();
    }

    private String problemDocumentMessage(KnowledgeDocument document) {
        if (document.status() == DocumentStatus.OCR_REQUIRED) {
            return PDF_OCR_REQUIRED_DOCUMENT_MESSAGE;
        }
        DocumentFailureResponse failure = failureCodec.fromDocument(document);
        return failure == null
                ? PARSE_FAILED_DOCUMENT_MESSAGE
                : failure.message();
    }

    private static int processingFailureCount(List<KnowledgeDocument> documents) {
        return (int) documents.stream()
                .filter(document -> document.status() != DocumentStatus.OCR_REQUIRED)
                .filter(document -> document.status() == DocumentStatus.FAILED || document.hasLastFailure())
                .count();
    }

    private static int documentStatusCount(List<KnowledgeDocument> documents, DocumentStatus status) {
        return (int) documents.stream()
                .filter(document -> document.status() == status)
                .count();
    }

    /**
     * 找出已经解析但尚未写入检索索引的文档。
     *
     * <p>只有 PARSED 文档才应进入索引，FAILED/OCR_REQUIRED 文档会由失败问题单独展示。</p>
     *
     * @param documents 目录下的文档记录
     * @return 未索引文档列表
     */
    private List<KnowledgeProblemDocumentResponse> unindexedDocuments(List<KnowledgeDocument> documents) {
        return documents.stream()
                .filter(document -> document.status() == DocumentStatus.PARSED)
                .filter(document -> document.indexedAt() == null)
                .map(document -> KnowledgeProblemDocumentResponse.from(
                        document,
                        "已解析但未进入索引，可能是供应商限流导致；请使用补写索引。"
                ))
                .toList();
    }

    /**
     * 统计已经完成索引的 SQLite chunk 数。
     *
     * <p>Lucene 一致性校验只能和已索引文档比较；未索引文档会单独形成 UNINDEXED_DOCUMENTS 问题。</p>
     *
     * @param documents 目录下的文档记录
     * @return 已索引文档包含的 chunk 数
     */
    private static int indexedChunkCount(List<KnowledgeDocument> documents) {
        return documents.stream()
                .filter(document -> document.status() == DocumentStatus.PARSED)
                .filter(document -> document.indexedAt() != null)
                .mapToInt(KnowledgeDocument::chunkCount)
                .sum();
    }

    /**
     * 构造目录范围的问题响应。
     *
     * @param code 问题类型
     * @param severity 严重级别，当前使用 ERROR/WARNING
     * @param message 用户可读说明
     * @param action 推荐维护动作
     * @param scopeId 目录 ID
     * @param count 受影响对象数量
     * @return 健康问题响应
     */
    private static KnowledgeHealthIssueResponse issue(
            KnowledgeHealthIssueCode code,
            String severity,
            String message,
            String action,
            String scopeId,
            int count
    ) {
        return issue(code, severity, message, action, scopeId, count, List.of());
    }

    /**
     * 构造目录或全库范围的问题响应，并附带少量样例供前端解释诊断结果。
     *
     * <p>examples 只用于展示，不作为修复动作的输入；重复和冲突资料仍由用户显式进入资料页处理。</p>
     *
     * @param code 问题类型
     * @param severity 严重级别
     * @param message 用户可读说明
     * @param action 推荐维护动作
     * @param scopeId 目录 ID；为空表示全库
     * @param count 受影响对象数量
     * @param examples 诊断样例，数量应保持很小
     * @return 健康问题响应
     */
    private static KnowledgeHealthIssueResponse issue(
            KnowledgeHealthIssueCode code,
            String severity,
            String message,
            String action,
            String scopeId,
            int count,
            List<String> examples
    ) {
        return issue(code, severity, message, action, scopeId, count, examples, List.of());
    }

    private static KnowledgeHealthIssueResponse issue(
            KnowledgeHealthIssueCode code,
            String severity,
            String message,
            String action,
            String scopeId,
            int count,
            List<String> examples,
            List<KnowledgeHealthIssueExampleResponse> exampleDetails
    ) {
        return new KnowledgeHealthIssueResponse(
                code,
                severity,
                message,
                action,
                scopeId == null ? KnowledgeFolderRunScopeType.ALL : KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                scopeId,
                count,
                examples,
                exampleDetails
        );
    }

    /**
     * 判断问题列表是否包含指定严重级别。
     *
     * @param issues 问题列表
     * @param severity 严重级别
     * @return 是否存在该级别问题
     */
    private static boolean hasSeverity(List<KnowledgeHealthIssueResponse> issues, String severity) {
        return issues.stream().anyMatch(issue -> severity.equals(issue.severity()));
    }

    private static int severityRank(String severity) {
        return switch (String.valueOf(severity)) {
            case "ERROR" -> 0;
            case "WARNING" -> 1;
            case "INFO" -> 2;
            default -> 3;
        };
    }

    /**
     * 取两个可空时间戳中的较大值。
     *
     * @param left 左侧时间戳
     * @param right 右侧时间戳
     * @return 两者最大值；两者都为空时返回空
     */
    private static Long maxNullable(Long left, Long right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private static int normalizePage(Integer page) {
        return page == null || page <= 0 ? 1 : page;
    }

    private static int normalizeRunPageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_RUN_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_RUN_PAGE_SIZE);
    }

    /**
     * 构造运行记录 scope 查找键。
     *
     * @param folderId 目录 ID
     * @return scopeType 和 scopeId 组合键
     */
    private static String scopeKey(String folderId) {
        return KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER.name() + ":" + folderId;
    }

    /**
     * 文件系统探针结果。
     *
     * <p>这里保存完整问题文档，目录详情直接展示；全库快照只取其中计数，避免响应过大。</p>
     */
    private record FolderHealthProbe(
            List<KnowledgeProblemDocumentResponse> missingLocalFiles,
            List<KnowledgeProblemDocumentResponse> staleLocalFiles,
            List<KnowledgeProblemDocumentResponse> newLocalFiles
    ) {
    }

    /**
     * 单个目录的全库列表快照。
     *
     * <p>该快照位于领域统计和 API 响应之间，用于复用目录状态、计数、最近运行记录和问题聚合。</p>
     */
    private record FolderHealthSnapshot(
            KnowledgeFolderSummary summary,
            KnowledgeHealthStatus status,
            int missingLocalFileCount,
            int staleLocalFileCount,
            int newLocalFileCount,
            int indexedChunkCount,
            KnowledgeFolderRunResponse lastRun,
            List<KnowledgeHealthIssueResponse> issues,
            List<KnowledgeDocument> documents
    ) {
        /**
         * 转换为全库健康列表中的目录摘要响应。
         *
         * @return 目录健康摘要
         */
        private KnowledgeFolderHealthSummaryResponse toSummaryResponse() {
            return new KnowledgeFolderHealthSummaryResponse(
                    summary.folder().id(),
                    summary.folder().displayName(),
                    summary.folder().folderPath(),
                    summary.folder().enabled(),
                    status,
                    summary.documentCount(),
                    summary.parsedCount(),
                    summary.failedCount(),
                    summary.unindexedCount(),
                    missingLocalFileCount,
                    staleLocalFileCount,
                    newLocalFileCount,
                    summary.chunkCount(),
                    summary.folder().lastIngestedAt(),
                    summary.folder().lastIndexedAt(),
                    lastRun
            );
        }
    }

    /**
     * 问答资料质量诊断快照。
     *
     * <p>这些计数只表达“可能干扰检索或回答”的风险，不代表资料被系统认证或否定。</p>
     */
    private record ContentQualitySnapshot(
            int duplicateDocumentCount,
            int versionConflictGroupCount,
            List<String> duplicateExamples,
            List<String> versionConflictExamples,
            List<KnowledgeHealthIssueExampleResponse> duplicateExampleDetails,
            List<KnowledgeHealthIssueExampleResponse> versionConflictExampleDetails
    ) {
    }

    private record VersionConflictGroupKey(
            String key,
            String label
    ) {
    }

    /**
     * 图谱派生视图的新鲜度快照。
     */
    private record GraphFreshnessSnapshot(
            int staleScopeCount,
            List<String> examples,
            List<KnowledgeHealthIssueExampleResponse> exampleDetails
    ) {
    }

    /**
     * Lucene 和 Embedding 的全局诊断快照。
     *
     * <p>readable=false 表示索引状态读取失败，此时 indexConsistent 固定为 false。</p>
     */
    private record IndexHealthSnapshot(
            long luceneDocumentCount,
            long luceneChunkCount,
            boolean embeddingConfigured,
            boolean indexConsistent,
            boolean readable,
            String errorMessage
    ) {
    }

    /**
     * 全库问题聚合器。
     *
     * <p>同一问题类型在多个目录出现时，只保留一条全库问题并累加 count；严重级别、动作和兜底文案
     * 取首个目录问题，要求同一 code 在 folderIssues 中保持一致配置。</p>
     */
    private static final class IssueAggregate {
        private final KnowledgeHealthIssueCode code;
        private final String severity;
        private final String message;
        private final String action;
        private int count;

        /**
         * 创建聚合器并继承单目录问题的展示配置。
         *
         * @param issue 单目录问题
         */
        private IssueAggregate(KnowledgeHealthIssueResponse issue) {
            this.code = issue.code();
            this.severity = issue.severity();
            this.message = issue.message();
            this.action = issue.action();
            this.count = 0;
        }

        /**
         * 累加同类问题的影响数量。
         *
         * @param issue 同一问题类型的目录问题
         */
        private void add(KnowledgeHealthIssueResponse issue) {
            this.count += issue.count();
        }

        /**
         * 转换为全库问题响应。
         *
         * @return 全库聚合问题
         */
        private KnowledgeHealthIssueResponse toResponse() {
            return new KnowledgeHealthIssueResponse(
                    code,
                    severity,
                    aggregateMessage(code, count, message),
                    action,
                    KnowledgeFolderRunScopeType.ALL,
                    null,
                    count
            );
        }

        /**
         * 生成全库聚合文案。
         *
         * @param code 问题类型
         * @param count 汇总数量
         * @param fallback 未覆盖问题类型时使用的单目录文案
         * @return 全库问题文案
         */
        private static String aggregateMessage(KnowledgeHealthIssueCode code, int count, String fallback) {
            return switch (code) {
                case FOLDER_NOT_FOUND -> "有 " + count + " 个目录当前不可访问。";
                case NO_DOCUMENTS -> "有 " + count + " 个启用目录没有文档记录。";
                case PARSE_FAILED -> "有 " + count + " 个文档解析失败。";
                case PDF_OCR_REQUIRED -> "有 " + count + " 个 PDF 需要 OCR 后才能进入知识库。";
                case UNINDEXED_DOCUMENTS -> "有 " + count + " 个已解析文档尚未进入索引。";
                case STALE_LOCAL_FILES -> "有 " + count + " 个本地文件疑似已变化。";
                case NEW_LOCAL_FILES -> "有 " + count + " 个本地新增文件尚未同步到知识库。";
                case MISSING_LOCAL_FILES -> "有 " + count + " 个已记录文件在本地不存在。";
                case DISABLED_FOLDER -> "有 " + count + " 个目录已停用。";
                default -> Objects.requireNonNullElse(fallback, "知识库存在需要处理的问题。");
            };
        }
    }
}
