package com.itqianchen.agentdesign.service.graph;

import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 清理应用重启遗留的图谱 run。
 */
@Component
public class KnowledgeGraphStartupCleaner implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private final KnowledgeGraphRepository repository;

    public KnowledgeGraphStartupCleaner(KnowledgeGraphRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        long now = System.currentTimeMillis();
        repository.failOrphanRuns("Application restarted before graph run completed", now);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
