package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalChatStageTrace;
import com.travel.ai.eval.dto.EvalChatToolTrace;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEvalTraceMapperTest {

    @Test
    void stageTraceMapper_mapsSuccess() {
        StageTrace trace = new StageTrace("PLAN", NodeStatus.SUCCESS, 12L, null, null, Map.of());

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("PLAN");
        assertThat(dto.getKind()).isEqualTo("workflow_node");
        assertThat(dto.getStatus()).isEqualTo("success");
        assertThat(dto.getElapsedMs()).isEqualTo(12L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getAttrs()).isNull();
    }

    @Test
    void stageTraceMapper_mapsSkippedWithReason() {
        StageTrace trace = new StageTrace(
                "TOOL",
                NodeStatus.SKIPPED,
                0L,
                null,
                "skipped_by_plan",
                Map.of("reason", "skipped_by_plan")
        );

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("TOOL");
        assertThat(dto.getKind()).isEqualTo("workflow_node");
        assertThat(dto.getStatus()).isEqualTo("skipped");
        assertThat(dto.getElapsedMs()).isEqualTo(0L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getAttrs()).containsEntry("reason", "skipped_by_plan");
    }

    @Test
    void stageTraceMapper_mapsFailedWithErrorCode() {
        StageTrace trace = new StageTrace(
                "RETRIEVE",
                NodeStatus.FAILED,
                8L,
                "VECTOR_STORE_ERROR",
                "failed",
                Map.of("exception", "IllegalStateException")
        );

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("RETRIEVE");
        assertThat(dto.getStatus()).isEqualTo("failed");
        assertThat(dto.getElapsedMs()).isEqualTo(8L);
        assertThat(dto.getErrorCode()).isEqualTo("VECTOR_STORE_ERROR");
        assertThat(dto.getAttrs()).containsEntry("exception", "IllegalStateException");
    }

    @Test
    void toolTraceMapper_mapsBasicFields() {
        ToolTrace trace = new ToolTrace(
                "market_data",
                "market_data",
                true,
                true,
                true,
                "ok",
                21L,
                null,
                "input:1",
                "output:1",
                Map.of("mock_mode", "true")
        );

        EvalChatToolTrace dto = RuntimeEvalTraceMapper.toEvalToolTrace(trace);

        assertThat(dto.getToolName()).isEqualTo("market_data");
        assertThat(dto.getConnector()).isEqualTo("market_data");
        assertThat(dto.getRequired()).isTrue();
        assertThat(dto.getUsed()).isTrue();
        assertThat(dto.getSucceeded()).isTrue();
        assertThat(dto.getOutcome()).isEqualTo("ok");
        assertThat(dto.getLatencyMs()).isEqualTo(21L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getInputRef()).isEqualTo("input:1");
        assertThat(dto.getOutputRef()).isEqualTo("output:1");
        assertThat(dto.getAttrs()).containsEntry("mock_mode", "true");
    }

    @Test
    void toolTraceMapper_omitsOrKeepsEmptyAttrsConsistently() {
        ToolTrace emptyAttrs = new ToolTrace(
                "weather",
                "weather",
                true,
                false,
                false,
                "disabled_by_policy",
                0L,
                "TOOL_POLICY_DISABLED",
                null,
                null,
                Map.of()
        );

        EvalChatToolTrace dto = RuntimeEvalTraceMapper.toEvalToolTrace(emptyAttrs);

        assertThat(dto.getToolName()).isEqualTo("weather");
        assertThat(dto.getOutcome()).isEqualTo("disabled_by_policy");
        assertThat(dto.getAttrs()).isNull();
    }

    // ---------------------------------------------------------------------
    // 形状绑定测试（保持 record 与 DTO 一致 / 守编译器看不见的搬运缝）
    //
    // 设计：ToolTrace / StageTrace record 是唯一权威形状源。下面两个测试用
    // 一个「饱和」的 record（每个分量都填非空、互不相同的值）跑 mapper，再用
    // 反射逐个分量核对：要么 DTO 有同名属性且 getter 非空（证明真的搬到了），
    // 要么该分量在「有意不搬」白名单里（一个明文记录的自觉决定）。
    //
    // 这道绑定补的是编译器瞎掉的那半：record 加了分量、DTO 也加了字段，但
    // mapper 漏写一行 setXxx —— 字段会静默蒸发、报告照样绿。本测试让它诚实报红，
    // 且报错能指名道姓是哪个分量没到。它只守「字段是否被搬运」（形状），不碰值，
    // 因此不绑死离线桩与真链路各自独立的构造来源。
    // ---------------------------------------------------------------------

    /** StageTrace 有意不搬进 DTO 的分量（连同明文理由）。 */
    private static final Set<String> STAGE_TRACE_INTENTIONALLY_DROPPED = Set.of(
            // message：DTO 的 V1 契约里没有 message 字段，阶段失败原因走 errorCode + attrs。
            "message"
    );

    /** ToolTrace 全部分量都应搬进 DTO，无有意丢弃。 */
    private static final Set<String> TOOL_TRACE_INTENTIONALLY_DROPPED = Set.of();

    @Test
    void toolTraceMapper_carriesEveryRecordComponentIntoDto() {
        ToolTrace saturated = new ToolTrace(
                "tool-name-x",
                "connector-x",
                true,
                true,
                true,
                "outcome-x",
                42L,
                "ERROR_CODE_X",
                "input-ref-x",
                "output-ref-x",
                Map.of("attr-k", "attr-v")
        );

        EvalChatToolTrace dto = RuntimeEvalTraceMapper.toEvalToolTrace(saturated);

        assertEveryComponentCarried(ToolTrace.class, dto, TOOL_TRACE_INTENTIONALLY_DROPPED);
    }

    @Test
    void stageTraceMapper_carriesEveryRecordComponentIntoDto() {
        StageTrace saturated = new StageTrace(
                "STAGE-X",
                NodeStatus.FAILED,
                42L,
                "ERROR_CODE_X",
                "message-x",
                Map.of("attr-k", "attr-v")
        );

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(saturated);

        assertEveryComponentCarried(StageTrace.class, dto, STAGE_TRACE_INTENTIONALLY_DROPPED);
    }

    /**
     * 对 record 的每个分量：要么在 dto 上找到同名 getter 且返回非空（已搬运），
     * 要么该分量名在 intentionallyDropped 里（明文豁免）。两者都不满足即报红，
     * 报错指名道姓是哪个分量。
     */
    private static void assertEveryComponentCarried(
            Class<?> recordType, Object dto, Set<String> intentionallyDropped) {
        for (RecordComponent component : recordType.getRecordComponents()) {
            String name = component.getName();
            if (intentionallyDropped.contains(name)) {
                continue;
            }
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            Object value;
            try {
                Method m = dto.getClass().getMethod(getter);
                value = m.invoke(dto);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(
                        recordType.getSimpleName() + " 分量 '" + name + "' 在 "
                                + dto.getClass().getSimpleName() + " 上没有对应的 " + getter
                                + "()。若是有意不搬，请加入 intentionallyDropped 白名单并注明理由。", e);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("反射调用 " + getter + "() 失败", e);
            }
            assertThat(value)
                    .as("%s 分量 '%s' 已声明但 mapper 未搬进 DTO（%s() 返回 null）—— "
                            + "字段静默蒸发，请在 mapper 补上 set%s，或加入 intentionallyDropped 白名单",
                            recordType.getSimpleName(), name, getter,
                            Character.toUpperCase(name.charAt(0)) + name.substring(1))
                    .isNotNull();
        }
    }
}
