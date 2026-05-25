package com.travel.ai.agent.prompt;

/**
 * Input values required to assemble the final LLM prompt for one agent turn.
 */
public record PromptAssemblyRequest(
        String currentUser,
        String toolPreface,
        String planJson,
        String promptBase
) {
}
