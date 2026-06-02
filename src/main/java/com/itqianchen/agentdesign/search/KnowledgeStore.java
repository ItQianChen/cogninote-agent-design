package com.itqianchen.agentdesign.search;

public interface KnowledgeStore {

    void indexDocument(IndexedDocument document);

    void deleteByDocumentId(String documentId);

    SearchResponse search(SearchRequest request);

    IndexStatusResponse status();

    RebuildIndexResponse rebuildAll();
}
