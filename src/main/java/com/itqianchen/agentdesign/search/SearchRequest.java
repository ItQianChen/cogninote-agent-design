package com.itqianchen.agentdesign.search;

import jakarta.validation.constraints.NotBlank;

public record SearchRequest(
        @NotBlank String query,
        SearchMode mode,
        Integer topK
) {
    public SearchMode modeOrDefault() {
        return mode == null ? SearchMode.HYBRID : mode;
    }
}
