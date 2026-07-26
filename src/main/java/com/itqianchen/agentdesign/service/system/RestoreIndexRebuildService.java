package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import com.itqianchen.agentdesign.service.index.IndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** 恢复成功后从 SQLite 事实源同步重建 Lucene。 */
@Component
public class RestoreIndexRebuildService implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RestoreIndexRebuildService.class);

    private final DataProtectionFileStore fileStore;
    private final DataProtectionService dataProtectionService;
    private final IndexService indexService;

    public RestoreIndexRebuildService(
            DataProtectionFileStore fileStore,
            DataProtectionService dataProtectionService,
            IndexService indexService
    ) {
        this.fileStore = fileStore;
        this.dataProtectionService = dataProtectionService;
        this.indexService = indexService;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        fileStore.pendingReindex().ifPresent(this::rebuild);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private void rebuild(PendingRestoreState state) {
        try {
            indexService.rebuild();
            PendingRestoreState completed = state.withPhase(
                    RestorePhase.COMPLETED,
                    "数据库和搜索索引已恢复。",
                    System.currentTimeMillis()
            );
            fileStore.writeState(completed);
            dataProtectionService.recordRestoreResult(completed, "COMPLETED", null);
            deletePreviousLucene(completed.restoreId());
        } catch (RuntimeException ex) {
            PendingRestoreState failed = state.withPhase(
                    RestorePhase.REINDEX_FAILED,
                    "数据库已恢复，但搜索索引重建失败，请从知识库维护页重试。",
                    System.currentTimeMillis()
            );
            fileStore.writeState(failed);
            dataProtectionService.recordRestoreResult(failed, "REINDEX_FAILED", "Lucene rebuild failed");
            log.error("restore_index_rebuild_failed restoreId={}", state.restoreId(), ex);
        } finally {
            fileStore.clearPendingReindex();
        }
    }

    private void deletePreviousLucene(String restoreId) {
        Path staleDirectory = fileStore.restoreWorkDir(restoreId).resolve("previous-lucene");
        if (!Files.exists(staleDirectory)) {
            return;
        }
        try (var paths = Files.walk(staleDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            log.warn("restore_previous_lucene_cleanup_failed restoreId={}", restoreId);
        }
    }
}
