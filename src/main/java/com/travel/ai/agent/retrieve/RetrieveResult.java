package com.travel.ai.agent.retrieve;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Retrieval stage output. It is pure data and does not mutate agent turn state.
 */
public record RetrieveResult(
        String currentUser,
        List<String> queries,
        List<Document> docs,
        String promptBase,
        String citationBlock,
        long rewriteMs,
        long retrieveMs
) {
    public RetrieveResult {
        currentUser = currentUser != null ? currentUser : "anonymous";
        queries = queries == null ? List.of() : List.copyOf(queries);
        docs = docs == null ? List.of() : List.copyOf(docs);
        promptBase = promptBase != null ? promptBase : "";
        citationBlock = citationBlock != null ? citationBlock : "";
    }
}
