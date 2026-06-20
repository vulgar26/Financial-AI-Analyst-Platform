package com.travel.ai.agent.guard;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 第二刀「语义相关性裁判」：判断<strong>已检索到的</strong>文档片段能否支撑回答用户问题。
 *
 * <p>第一刀（{@link RetrieveEmptyHitGate}）处理的是「零命中」——纯规则、无 I/O、确定。
 * 本裁判处理第一刀够不着的情况：<em>检索非空，但捞回来的片段与问题不相干</em>
 * （RAG 暗坑：召回了 ≠ 召回对了）。相关性是语义问题，{@code docs.isEmpty()} 这种布尔量度不到，
 * 故必须借助 LLM 判断。因此它<strong>不</strong>塞进纯规则的 {@link RetrieveEmptyHitGate}，
 * 避免「会幻觉、有 I/O、不确定」的 LLM 污染第一刀的可测性；两刀在 adapter 编排层组合。</p>
 *
 * <p><strong>职责被刻意锁死</strong>：裁判只输出「相关与否 + 一句理由」，碰不到最终答案
 * （答案永远由主链路基于真 docs 生成）。它只决定「要不要换个问法再检一次」，决定权小、可审计——
 * 这是「拿幻觉判幻觉」陷阱的防线：不让一个会编的东西去改写另一个会编的东西的产物。</p>
 *
 * <p><strong>fail-safe 契约</strong>：实现遇到 LLM 超时/鉴权失败/空返回等任何异常，必须默认
 * {@link Verdict#passThrough()}（relevant=true、放行），绝不因裁判自身不可用而触发 replan——
 * 否则一次网络抖动就能让正常请求空转重查，把外部不稳定变成功能 bug。</p>
 */
public interface RetrievalRelevanceJudge {

    /**
     * 裁判结论。
     *
     * @param relevant 检索片段能否支撑回答；fail-safe 不可用时为 true（放行）
     * @param reason   一句话理由，供 trace/日志归因
     * @param available 裁判是否真正给出了判断；false 表示 LLM 不可用、relevant 是 fail-safe 默认值
     */
    record Verdict(boolean relevant, String reason, boolean available) {
        /** LLM 不可用时的 fail-safe：放行、标记 available=false。 */
        public static Verdict passThrough() {
            return new Verdict(true, "judge_unavailable", false);
        }

        public static Verdict relevant(String reason) {
            return new Verdict(true, reason, true);
        }

        public static Verdict notRelevant(String reason) {
            return new Verdict(false, reason, true);
        }
    }

    /**
     * @param userQuestion 用户原始问题
     * @param docs         RETRIEVE 合并后的非空文档列表
     * @return 裁判结论；任何内部失败都应回退为 {@link Verdict#passThrough()}，不得抛出
     */
    Verdict judge(String userQuestion, List<Document> docs);
}
