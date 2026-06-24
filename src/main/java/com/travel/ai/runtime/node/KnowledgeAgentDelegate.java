package com.travel.ai.runtime.node;

import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.retrieve.EvidencePackage;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Knowledge Agent 子图与外部 I/O 的边界（检索、相关性裁判）。
 *
 * <p>子图节点只拥有「调度/决策」语义（要不要 replan、换不换问法）；真正的向量检索与 LLM 裁判
 * 通过本接口外置，便于单测注入假实现、也便于阶段 4 接真 service。这沿用现有 thin-node 的
 * delegate 模式。</p>
 */
public interface KnowledgeAgentDelegate {

    /**
     * 执行一趟检索，产出证据袋。阈值/改写提示等策略由实现按 {@code attempt}/{@code relevanceReplan}
     * 自行决定（实现闭包持有 userMessage 等输入）。
     *
     * @param attempt          本轮已 replan 次数（0 = 首检），实现据此决定是否降级阈值
     * @param relevanceReplan  本次是否因「检索到但不相关」而重检，实现据此决定是否给 query 加改写提示
     * @return 证据袋；实现不得返回 null（无命中也返回空袋）
     */
    EvidencePackage retrieve(int attempt, boolean relevanceReplan);

    /**
     * 对已检索到的非空文档做语义相关性裁判。实现闭包持有 userMessage。
     * 契约同 {@link RetrievalRelevanceJudge}：任何内部失败回退为 {@link RetrievalRelevanceJudge.Verdict#passThrough()}。
     */
    RetrievalRelevanceJudge.Verdict judge(List<Document> docs);
}
