package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParseCheckpoint;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import java.util.List;

/** 将通用 parser 检查点契约绑定到单个文档的 SQLite 实现。 */
public final class PersistentDocumentParseCheckpoint implements DocumentParseCheckpoint {

    private final DocumentOcrCheckpointService checkpointService;
    private final KnowledgeDocument placeholder;

    public PersistentDocumentParseCheckpoint(
            DocumentOcrCheckpointService checkpointService,
            KnowledgeDocument placeholder
    ) {
        this.checkpointService = checkpointService;
        this.placeholder = placeholder;
    }

    @Override
    public boolean durable() {
        return true;
    }

    @Override
    public List<ParsedSection> prepare(String parserSignature, int totalSections) {
        return checkpointService.prepare(placeholder, parserSignature, totalSections);
    }

    @Override
    public void save(ParsedSection section) {
        checkpointService.savePage(placeholder.id(), section);
    }
}
