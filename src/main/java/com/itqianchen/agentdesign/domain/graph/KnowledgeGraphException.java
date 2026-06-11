package com.itqianchen.agentdesign.domain.graph;

/**
 * 知识图谱模块的可预期业务异常。
 */
public class KnowledgeGraphException extends RuntimeException {

    public KnowledgeGraphException(String message) {
        super(message);
    }

    public KnowledgeGraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
