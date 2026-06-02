package com.itqianchen.agentdesign.search;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final KnowledgeStore knowledgeStore;

    public SearchController(KnowledgeStore knowledgeStore) {
        this.knowledgeStore = knowledgeStore;
    }

    @PostMapping
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return knowledgeStore.search(request);
    }
}
