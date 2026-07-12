package com.itqianchen.agentdesign.domain.interfaces.ingestion;

import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import java.util.List;

/**
 * 长耗时文档解析的增量进度边界。
 *
 * <p>解析器只通过该契约读取和报告已完成 section，不感知 SQLite 等持久化实现。调用方必须保证
 * save 成功返回后结果已经持久化，才能在后续失败时安全跳过对应 section。</p>
 */
public interface DocumentParseCheckpoint {

    /**
     * 表示 save 返回后结果是否已经跨进程持久化。
     *
     * @return 可用于失败续传时为 true
     */
    boolean durable();

    /**
     * 准备当前解析签名并返回可复用的已完成 section。
     *
     * @param parserSignature 会影响解析输出的稳定签名
     * @param totalSections 当前文档 section 总数
     * @return 已持久化的 section，按来源顺序排列
     */
    List<ParsedSection> prepare(String parserSignature, int totalSections);

    /**
     * 持久化一个已成功处理的 section。
     *
     * @param section 已完成 section；pageNumber 必须存在
     */
    void save(ParsedSection section);

    /** 返回不保存进度的默认实现，供直接 parser 调用和非增量解析场景使用。 */
    static DocumentParseCheckpoint none() {
        return NoOpDocumentParseCheckpoint.INSTANCE;
    }

    enum NoOpDocumentParseCheckpoint implements DocumentParseCheckpoint {
        INSTANCE;

        @Override
        public boolean durable() {
            return false;
        }

        @Override
        public List<ParsedSection> prepare(String parserSignature, int totalSections) {
            return List.of();
        }

        @Override
        public void save(ParsedSection section) {
            // 直接调用 parser 时没有导入事务上下文，完成结果只保留在当前解析返回值中。
        }
    }
}
