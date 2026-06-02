package com.itqianchen.agentdesign.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final KnowledgeStore knowledgeStore;

    public IndexController(KnowledgeStore knowledgeStore) {
        this.knowledgeStore = knowledgeStore;
    }

    @GetMapping("/status")
    public IndexStatusResponse status() {
        return knowledgeStore.status();
    }

    @PostMapping("/rebuild")
    public RebuildIndexResponse rebuild() {
        return knowledgeStore.rebuildAll();
    }
}
