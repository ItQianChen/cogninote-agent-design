package com.itqianchen.agentdesign.service.graph;

import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 清理应用重启遗留的图谱 run。
 *
 * <p>图谱生成没有跨进程恢复机制，应用启动完成后需要把上次未结束的 run 标记为失败。</p>
 */
@Component
public class KnowledgeGraphStartupCleaner implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private final KnowledgeGraphRepository repository;

    /**
     * 注入图谱仓储。
     *
     * @param repository 图谱仓储
     */
    public KnowledgeGraphStartupCleaner(KnowledgeGraphRepository repository) {
        this.repository = repository;
    }

    /**
     * 应用启动完成后清理孤儿运行记录。
     *
     * @param event Spring Boot 启动完成事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        long now = System.currentTimeMillis();
        repository.failOrphanRuns("上次知识图谱生成因应用重启被中断，请重新生成。", now);
    }

    /**
     * 将清理动作排在启动链路末尾。
     *
     * <p>等数据库初始化和迁移完成后再写入失败状态，避免启动早期表结构未就绪。</p>
     *
     * @return 最低优先级
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
