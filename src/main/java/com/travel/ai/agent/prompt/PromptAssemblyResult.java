package com.travel.ai.agent.prompt;

/**
 * Assembled prompt plus intermediate blocks for focused compatibility tests.
 */
public record PromptAssemblyResult(
        String finalPromptForLlm,
        String profileBlock,
        String planBlock,
        String financeOutputGuardBlock,
        boolean financeOutputGuardApplied
) {
    public PromptAssemblyResult {
        finalPromptForLlm = finalPromptForLlm != null ? finalPromptForLlm : "";
        profileBlock = profileBlock != null ? profileBlock : "";
        planBlock = planBlock != null ? planBlock : "";
        financeOutputGuardBlock = financeOutputGuardBlock != null ? financeOutputGuardBlock : "";
    }
}
