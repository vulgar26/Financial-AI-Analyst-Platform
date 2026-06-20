package com.travel.ai.agent.guard;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 主线「检索零命中」门控判定（纯规则，无 I/O）：与 {@code app.rag.empty-hits-behavior}、检索条数、工具块<strong>有效 payload</strong>组合出是否跳过 LLM。
 * <p>
 * 大白话：知识库没命中时，默认不让大模型「瞎编」；若工具真的返回了数据（BEGIN/END 之间有正文），才放行让模型结合工具观察写回答。
 * <p>
 * 若仅有工具失败时的元数据头、payload 为空，则与「完全没工具」同等对待，仍走澄清短路（避免无数据时编造行情/数字类幻觉）。
 */
public final class RetrieveEmptyHitGate {

    public static final String ERROR_CODE_RETRIEVE_EMPTY = "RETRIEVE_EMPTY";
    /** 工具块存在但 BEGIN_TOOL_DATA 内无可用正文（常见：outcome=ERROR 且 observation 为空）。 */
    public static final String ERROR_CODE_TOOL_NO_USABLE_PAYLOAD = "TOOL_NO_USABLE_PAYLOAD";

    public enum Reason {
        /** 纯零命中、无有效工具数据，下发 RAG 澄清。 */
        APPLIED_CLARIFY_RAG_EMPTY,
        /** 零命中且工具元数据在但 payload 空，下发工具不可用澄清。 */
        APPLIED_CLARIFY_TOOL_NO_PAYLOAD,
        /** BEGIN_TOOL_DATA 内有非空正文，不拦。 */
        SKIPPED_TOOL_SUBSTANTIVE_PAYLOAD,
        /** 检索有命中。 */
        SKIPPED_HAS_RETRIEVAL_HITS,
        /** 配置非 clarify（如 allow_answer）。 */
        SKIPPED_NOT_CLARIFY_MODE
    }

    /**
     * @param skipGateErrorCode 仅当 {@code skipLlm} 为 true 时有值，供日志与客户端归因。
     */
    public record Decision(boolean skipLlm, String clarifyBody, Reason reason, String skipGateErrorCode) {
        private static Decision noSkip(Reason reason) {
            return new Decision(false, null, reason, null);
        }
    }

    private RetrieveEmptyHitGate() {
    }

    /**
     * @param docs               RETRIEVE 合并后的文档列表（可为 null）
     * @param toolPreface        TOOL 阶段拼入 prompt 的前缀（无工具时为空串）
     * @param emptyHitsBehavior  {@code clarify} | {@code allow_answer} 等
     */
    public static Decision decide(List<Document> docs, String toolPreface, String emptyHitsBehavior) {
        String mode = emptyHitsBehavior == null ? "" : emptyHitsBehavior.trim();
        if (!"clarify".equalsIgnoreCase(mode)) {
            return Decision.noSkip(Reason.SKIPPED_NOT_CLARIFY_MODE);
        }
        boolean zeroHits = docs == null || docs.isEmpty();
        if (!zeroHits) {
            return Decision.noSkip(Reason.SKIPPED_HAS_RETRIEVAL_HITS);
        }
        String pre = toolPreface == null ? "" : toolPreface;
        if (ToolPrefacePayload.hasSubstantiveBody(pre)) {
            return Decision.noSkip(Reason.SKIPPED_TOOL_SUBSTANTIVE_PAYLOAD);
        }
        if (!pre.isBlank()) {
            String body = """
                    工具已调用但未返回可用数据（可能为网络或服务异常），无法提供实时信息；当前知识库也未命中相关条目。请稍后重试或换一种问法。
                    （error_code=%s）
                    """.formatted(ERROR_CODE_TOOL_NO_USABLE_PAYLOAD).trim();
            return new Decision(true, body, Reason.APPLIED_CLARIFY_TOOL_NO_PAYLOAD, ERROR_CODE_TOOL_NO_USABLE_PAYLOAD);
        }
        String body = """
                检索零命中：请补充关键词/范围/上下文，或提供可引用资料后再继续。
                （error_code=%s）
                """.formatted(ERROR_CODE_RETRIEVE_EMPTY).trim();
        return new Decision(true, body, Reason.APPLIED_CLARIFY_RAG_EMPTY, ERROR_CODE_RETRIEVE_EMPTY);
    }
}
