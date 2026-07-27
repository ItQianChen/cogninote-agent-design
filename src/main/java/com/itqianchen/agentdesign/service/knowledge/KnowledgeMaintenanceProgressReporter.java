package com.itqianchen.agentdesign.service.knowledge;

import com.itqianchen.agentdesign.service.task.DurableTaskContext;
import org.springframework.stereotype.Component;

/**
 * 维护任务线程内的轻量进度上报器。
 *
 * <p>Embedding 网关处在检索模块里，不应该依赖耐久任务模块；handler 执行期间把 context
 * 放进 ThreadLocal，外部调用或普通搜索没有上下文时上报会自动忽略。</p>
 */
@Component
public class KnowledgeMaintenanceProgressReporter {

    private final ThreadLocal<DurableTaskContext> currentContext = new ThreadLocal<>();

    public <T> T withRun(DurableTaskContext context, MaintenanceOperation<T> operation) {
        DurableTaskContext previousContext = currentContext.get();
        currentContext.set(context);
        try {
            return operation.execute();
        } finally {
            if (previousContext == null) {
                currentContext.remove();
            } else {
                currentContext.set(previousContext);
            }
        }
    }

    public void reportEmbeddingRateLimit(String message) {
        DurableTaskContext context = currentContext.get();
        if (context == null) {
            return;
        }
        context.progress("INDEXING", 0, 1, message, null);
    }

    @FunctionalInterface
    public interface MaintenanceOperation<T> {
        T execute();
    }
}
