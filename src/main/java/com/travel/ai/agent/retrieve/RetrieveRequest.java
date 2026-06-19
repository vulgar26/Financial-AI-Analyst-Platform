package com.travel.ai.agent.retrieve;

/**
 * Input values for one retrieval stage execution.
 */
public record RetrieveRequest(
        String userMessage,
        String currentUser,
        String requestId,
        int maxContextDocs,
        int topKPerQuery,
        double similarityThreshold
) {
}
