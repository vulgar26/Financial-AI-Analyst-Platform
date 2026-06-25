package com.travel.ai.agent.retrieve;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Knowledge Agent → Analyst Agent 之间唯一合法的产物契约（「文件袋」）。
 *
 * <p>这是多 Agent 隔离的前提：Analyst 只能读这个袋子，不能反向读 Knowledge 内部的工作内存
 * （userFilter、replan 计数、改写提示等）。把「实际跨边界流动的字段」显式成一个有名字的类型，
 * 隔离才不是假隔离——编译器替我们挡住越界访问。</p>
 *
 * <p>形状上是 {@link RetrieveResult} 砍掉 {@code rewriteMs} 后的子集：rewriteMs 是 Knowledge
 * 自己的检索耗时细节，Analyst 用不着，故不进袋子。这不是巧合——RetrieveService 本来就在返回
 * 一个纯数据对象，我们只是给它正名为「Knowledge 的产物契约」。</p>
 *
 * <p>阶段 1 仅新增此类型并做「打包→立刻解包」的等价重构，不改任何行为；阶段 2 起才把「打包」
 * 这个动作搬进 KnowledgeAgentNode 内部。</p>
 */
public record EvidencePackage(
        String currentUser,
        List<String> queries,
        List<Document> docs,
        String promptBase,
        String citationBlock,
        long retrieveMs
) {
    public EvidencePackage {
        currentUser = currentUser != null ? currentUser : "anonymous";
        queries = queries == null ? List.of() : List.copyOf(queries);
        docs = docs == null ? List.of() : List.copyOf(docs);
        promptBase = promptBase != null ? promptBase : "";
        citationBlock = citationBlock != null ? citationBlock : "";
    }

    /**
     * 从检索阶段的原始产出装袋。rewriteMs 刻意不带入（Knowledge 内部细节）。
     */
    public static EvidencePackage from(RetrieveResult result) {
        if (result == null) {
            return new EvidencePackage(null, List.of(), List.of(), "", "", 0L);
        }
        return new EvidencePackage(
                result.currentUser(),
                result.queries(),
                result.docs(),
                result.promptBase(),
                result.citationBlock(),
                result.retrieveMs()
        );
    }
}
