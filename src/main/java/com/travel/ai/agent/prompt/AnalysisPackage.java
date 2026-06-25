package com.travel.ai.agent.prompt;

/**
 * Analyst Agent → WRITE 之间的产物契约（第二个「文件袋」）。
 *
 * <p>延续 {@link com.travel.ai.agent.retrieve.EvidencePackage} 的「最小交接面」原则：只装 WRITE
 * 真正要用的三样——最终 prompt、是否跳过 LLM、跳过时的澄清正文。工具 trace、policyEvents 等
 * 可观测数据<strong>不进袋</strong>，走外层另一条收集通道，避免袋子退化成「杂物袋」、避免 Agent
 * 间产物契约和可观测耦合。</p>
 *
 * <p>{@code skipLlm=true} 时 WRITE 直接下发 {@code clarifyBody} 短路，不调大模型；否则用
 * {@code finalPromptForLlm} 走正常生成。</p>
 */
public record AnalysisPackage(
        String finalPromptForLlm,
        boolean skipLlm,
        String clarifyBody
) {
    public AnalysisPackage {
        finalPromptForLlm = finalPromptForLlm != null ? finalPromptForLlm : "";
        clarifyBody = clarifyBody != null ? clarifyBody : "";
    }
}
