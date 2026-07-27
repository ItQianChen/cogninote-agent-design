package com.itqianchen.agentdesign.service.task;

/** 向领域 handler 暴露进度和不透明 checkpoint 写入边界。 */
public final class DurableTaskContext {

    private final String runId;
    private final int attempt;
    private final ProgressSink progressSink;

    public DurableTaskContext(String runId, int attempt, ProgressSink progressSink) {
        this.runId = runId;
        this.attempt = attempt;
        this.progressSink = progressSink;
    }

    public String runId() {
        return runId;
    }

    public int attempt() {
        return attempt;
    }

    public void progress(
            String step,
            long progressCurrent,
            long progressTotal,
            String currentItem,
            String checkpointJson
    ) {
        progressSink.update(step, progressCurrent, progressTotal, currentItem, checkpointJson);
    }

    @FunctionalInterface
    public interface ProgressSink {
        void update(String step, long progressCurrent, long progressTotal, String currentItem, String checkpointJson);
    }
}
