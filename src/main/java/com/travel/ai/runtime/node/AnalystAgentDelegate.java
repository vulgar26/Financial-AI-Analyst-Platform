package com.travel.ai.runtime.node;

import com.travel.ai.agent.retrieve.EvidencePackage;

/**
 * Analyst Agent 子图与外部 I/O 的边界（工具调用、护栏决策、prompt 拼装）。
 *
 * <p>沿用现有 thin-node 的 delegate 模式：子图节点只拥有「调度/决策」语义，真正的工具调用与
 * LLM/规则判定通过本接口外置。实现闭包持有 userMessage、currentUser 等输入。</p>
 *
 * <p><strong>可观测走外层</strong>：guard 产生的 policyEvents、工具 trace 等不经由本接口返回，
 * 由实现侧自行收集到外层（与「最小交接面」一致，可观测不混进 Agent 产物契约）。</p>
 */
public interface AnalystAgentDelegate {

    /**
     * 调用工具阶段，产出工具前言（toolPreface），由实现保存在闭包/外层供后续 guard 与 prompt 使用。
     * 工具 trace、policyEvents 由实现侧收集到外层。
     */
    void invokeTool();

    /**
     * 执行护栏决策（纯规则：检索袋 + 工具前言 → 是否跳过 LLM / 澄清正文）。
     * policyEvents 由实现收集到外层；本方法只回传袋子要用的两项。
     *
     * @param evidence 来自 Knowledge Agent 的证据袋（docs 用于零命中判定）
     */
    GuardOutcome guard(EvidencePackage evidence);

    /**
     * 拼装最终 prompt。实现读 currentUser/toolPreface/planJson + 证据袋的 promptBase。
     *
     * @param evidence 证据袋（promptBase 是检索上下文 + 用户问题）
     * @return 送入 LLM 的最终 prompt
     */
    String assemblePrompt(EvidencePackage evidence);

    /** 护栏决策中、产物契约真正要用的两项（policyEvents 不在此，走外层）。 */
    record GuardOutcome(boolean skipLlm, String clarifyBody) {
        public GuardOutcome {
            clarifyBody = clarifyBody != null ? clarifyBody : "";
        }
    }
}
