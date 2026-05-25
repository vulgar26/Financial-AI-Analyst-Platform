package com.travel.ai.agent.prompt;

import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.profile.UserProfileService;

/**
 * Pure prompt assembly boundary for the mainline finance analyst workflow.
 *
 * <p>This service does not mutate agent turn state, does not emit SSE, does not
 * write Redis, and does not generate policy events.</p>
 */
public final class PromptAssemblyService {

    static final String FINANCE_OUTPUT_GUARD_BLOCK = """
            【金融输出约束】
            - 本轮行情/市场数据来自本地 mock，不是实时数据。
            - 这些数据不可用于交易决策，最终回答中必须包含“不可用于交易决策”或“不能作为交易依据”。
            - 不得输出买入、卖出、持有、仓位、止盈止损等交易建议。
            - 回答结尾必须说明：内容仅供研究和教育参考，不构成投资建议。

            """;

    private final UserProfileService userProfileService;

    public PromptAssemblyService(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    public PromptAssemblyResult assemble(PromptAssemblyRequest request) {
        String currentUser = request != null ? request.currentUser() : null;
        String profileBlock = userProfileService != null
                ? nullToEmpty(userProfileService.buildPromptPrefixBlock(currentUser))
                : "";
        String toolPreface = nullToEmpty(request != null ? request.toolPreface() : null);
        String planJson = nullToEmpty(request != null ? request.planJson() : null);
        String promptBase = nullToEmpty(request != null ? request.promptBase() : null);

        String planBlock = !planJson.isBlank()
                ? "【本轮执行计划（结构化，须遵守）】\n" + planJson + "\n\n"
                : "";
        boolean financeOutputGuardApplied = GuardDecisionService.shouldApplyMarketDataOutputGuard(toolPreface);
        String financeOutputGuardBlock = financeOutputGuardApplied ? FINANCE_OUTPUT_GUARD_BLOCK : "";
        String finalPromptForLlm = profileBlock + toolPreface + financeOutputGuardBlock + planBlock + promptBase;

        return new PromptAssemblyResult(
                finalPromptForLlm,
                profileBlock,
                planBlock,
                financeOutputGuardBlock,
                financeOutputGuardApplied
        );
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
