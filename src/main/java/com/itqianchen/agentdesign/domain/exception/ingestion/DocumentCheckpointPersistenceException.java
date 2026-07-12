package com.itqianchen.agentdesign.domain.exception.ingestion;

/** OCR 页级检查点无法写入本地数据库。 */
public class DocumentCheckpointPersistenceException extends DocumentParseException {

    public DocumentCheckpointPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
