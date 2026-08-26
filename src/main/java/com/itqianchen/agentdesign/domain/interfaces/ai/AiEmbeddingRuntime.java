package com.itqianchen.agentdesign.domain.interfaces.ai;

import java.util.List;

/**
 * 统一 Embedding 模型调用接口。
 *
 * <p>query and document are modeled separately while protocol details stay behind this interface.</p>
 */
public interface AiEmbeddingRuntime {

    /**
     * 为检索 query 生成向量。
     *
     * <p>实现可以为 query 使用与 document 不同的 provider 参数，调用方不能用 document embedding 代替。</p>
     *
     * @param query 用户检索词或改写后的检索语句
     * @return 与当前模型维度一致的向量
     */
    float[] embedQuery(String query);

    /**
     * 为文档 chunk 批量生成向量。
     *
     * <p>返回顺序必须与输入文本顺序一致，索引写入依赖这个顺序回填 chunkId。</p>
     *
     * @param texts 待索引 chunk 文本
     * @return 与输入顺序一一对应的向量列表
     */
    List<float[]> embedDocuments(List<String> texts);
}
