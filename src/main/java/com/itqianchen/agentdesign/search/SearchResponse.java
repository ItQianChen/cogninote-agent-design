package com.itqianchen.agentdesign.search;

import java.util.List;

public record SearchResponse(
        String query,
        SearchMode mode,
        List<SearchHitResponse> hits
) {
}
