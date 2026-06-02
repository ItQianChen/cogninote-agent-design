package com.itqianchen.agentdesign.search;

import java.util.List;

public interface EmbeddingGateway {

    boolean isAvailable();

    int dimensions();

    List<float[]> embedBatch(List<String> texts);
}
