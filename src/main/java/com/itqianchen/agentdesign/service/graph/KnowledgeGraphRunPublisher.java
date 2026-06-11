package com.itqianchen.agentdesign.service.graph;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识图谱 run 的 SSE 发布器。
 * <p>SSE 是观察通道，不拥有后台任务生命周期；取消必须通过显式 cancel 接口表达。</p>
 */
@Component
public class KnowledgeGraphRunPublisher {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphRunPublisher.class);
    private static final long SSE_TIMEOUT_DISABLED = 0L;

    private final Map<String, Set<SseEmitter>> emittersByRunId = new ConcurrentHashMap<>();
    private final Set<String> cancelledRunIds = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe(String runId, Object initialSnapshot, boolean terminalSnapshot) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_DISABLED);
        Set<SseEmitter> emitters = emittersByRunId.computeIfAbsent(runId, ignored -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> {
            log.debug("knowledge_graph_sse_closed runId={}", runId, error);
            remove(runId, emitter);
        });
        sendOne(runId, emitter, "graph-run-snapshot", initialSnapshot);
        if (terminalSnapshot) {
            completeRun(runId);
        }
        return emitter;
    }

    public void cancel(String runId) {
        cancelledRunIds.add(runId);
        publish(runId, "graph-run-cancel-requested", Map.of("runId", runId));
    }

    public boolean isCancelled(String runId) {
        return cancelledRunIds.contains(runId);
    }

    public void clearCancellation(String runId) {
        cancelledRunIds.remove(runId);
    }

    public void publishStarted(String runId, KnowledgeGraphRunProgress progress) {
        publish(runId, "graph-run-started", progress);
    }

    public void publishProgress(String runId, KnowledgeGraphRunProgress progress) {
        publish(runId, "graph-run-progress", progress);
    }

    public void publishViewReady(String runId, String viewType) {
        publish(runId, "graph-run-view-ready", Map.of("runId", runId, "viewType", viewType));
    }

    public void publishCompleted(String runId, Object data) {
        publish(runId, "graph-run-completed", data);
        completeRun(runId);
    }

    public void publishFailed(String runId, Object data) {
        publish(runId, "graph-run-failed", data);
        completeRun(runId);
    }

    public void publishCancelled(String runId, Object data) {
        publish(runId, "graph-run-cancelled", data);
        completeRun(runId);
    }

    private void publish(String runId, String eventName, Object data) {
        Set<SseEmitter> emitters = emittersByRunId.get(runId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sendOne(runId, emitter, eventName, data);
        }
    }

    private void sendOne(String runId, SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException | IllegalStateException ex) {
            remove(runId, emitter);
            log.debug("knowledge_graph_sse_send_failed runId={} event={}", runId, eventName, ex);
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter 可能已被容器关闭。
            }
        }
    }

    private void completeRun(String runId) {
        Set<SseEmitter> emitters = emittersByRunId.remove(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // emitter 可能已被容器关闭。
            }
        }
        clearCancellation(runId);
    }

    private void remove(String runId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByRunId.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByRunId.remove(runId, emitters);
        }
    }
}
