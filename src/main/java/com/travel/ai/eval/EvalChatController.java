package com.travel.ai.eval;

import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.eval.dto.EvalChatRequest;
import com.travel.ai.eval.dto.EvalChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 评测专用入口（P0）：{@code POST /api/v1/eval/chat}，<strong>非流式</strong>、一次性 JSON 返回。
 * <p>
 * <b>网关</b>：须带请求头 {@code X-Eval-Gateway-Key}，与 {@code app.eval.gateway-key}（环境变量 {@code APP_EVAL_GATEWAY_KEY}）一致；
 * 与参与 membership HMAC 的 {@code X-Eval-Token} 分离。未配置网关密钥时评测路径一律 401。
 *
 * <h2>最小请求示例（curl，Windows PowerShell 可把单引号改为双引号并转义内部引号）</h2>
 * <pre>{@code
 * curl -s -X POST "http://localhost:8080/api/v1/eval/chat" \
 *   -H "Content-Type: application/json" \
 *   -d '{
 *     "query": "帮我规划上海三日游",
 *     "mode": "AGENT",
 *     "conversation_id": "day1-demo-conv"
 *   }'
 * }</pre>
 *
 * <h2>Day3 证据 ①：正常 query（走满线性占位流水线）</h2>
 * <p>{@code step_count == stage_order.length}（此处为 5）；普通流水线 {@code replan_count} 为 0，受控 replan 场景至多 1（见下）。</p>
 * <pre>{@code
 * {
 *   "answer": "Day3：meta 可观测稳定…",
 *   "behavior": "answer",
 *   "latency_ms": 3,
 *   "capabilities": { ... },
 *   "meta": {
 *     "mode": "AGENT",
 *     "request_id": "550e8400-e29b-41d4-a716-446655440000",
 *     "stage_order": ["PLAN","RETRIEVE","TOOL","GUARD","WRITE"],
 *     "step_count": 5,
 *     "replan_count": 0
 *   }
 * }
 * }</pre>
 *
 * <h2>Day3 证据 ②：空/空白 query（clarify，不跑流水线）</h2>
 * <p>{@code stage_order} 为空、{@code step_count=0}；{@code replan_count} 仍为 0。</p>
 * <pre>{@code
 * {
 *   "behavior": "clarify",
 *   "meta": {
 *     "mode": "EVAL",
 *     "request_id": "...",
 *     "stage_order": [],
 *     "step_count": 0,
 *     "replan_count": 0
 *   }
 * }
 * }</pre>
 *
 * <h2>Day5：{@code plan_parse_attempts} / {@code plan_parse_outcome}（repair once）</h2>
 * <p>非空 {@code query} 会先解析 plan（未传 {@code plan_raw} 时用内置合法默认 JSON）；失败则最多 repair 一次。
 * {@code success|repaired|failed}；连续失败时 {@code behavior=clarify}、{@code error_code=PARSE_ERROR}。</p>
 *
 * <h2>Day6：{@code eval_tool_scenario} 串行工具 stub + 超时降级（HTTP 200）</h2>
 * <p>{@code eval_tool_scenario=success}：{@code behavior=tool}，{@code tool.used=true}，{@code tool.outcome=ok}，
 * {@code meta.tool_calls_count=1}，{@code meta.tool_outcome=ok}。</p>
 * <p>{@code eval_tool_scenario=timeout|error}：仍 200；{@code behavior=answer}，{@code error_code=TOOL_TIMEOUT|TOOL_ERROR}，
 * {@code tool.used=true}，{@code tool.outcome=timeout|error}。</p>
 *
 * <h2>Day7：{@code eval_rag_scenario} 空命中 / 低置信门控（P0 无 score 阈值）</h2>
 * <p>{@code eval_rag_scenario=empty}：{@code meta.low_confidence=true}，{@code meta.low_confidence_reasons[]} 非空，
 * {@code meta.retrieve_hit_count=0}，{@code error_code=RETRIEVE_EMPTY}，{@code behavior=clarify}。</p>
 * <p>{@code eval_rag_scenario=low_conf}：同上低置信与 {@code low_confidence_reasons}，命中数 stub 为 1；不设 {@code error_code}（与空命中区分）。</p>
 *
 * <h2>Day9：输入鲁棒性（归因稳定）</h2>
 * <p>Plan 解析成功后对 {@code query} 做高置信安全筛查：典型对抗句式 → {@code behavior=deny}，
 * {@code error_code=PROMPT_INJECTION_BLOCKED|TOOL_OUTPUT_INJECTION_QUERY_BLOCKED}；长上下文诱导演练句 → {@code clarify}（无 {@code error_code}）。</p>
 * <p>证据 case（{@code p0-dataset-v0.jsonl}）：{@code p0_v0_attack_prompt_injection_001} 期望 deny；
 * {@code p0_v0_attack_tool_output_injection_001} 期望 deny；{@code p0_v0_attack_long_context_001} 期望 clarify。</p>
 *
 * <h2>Day10：C 线 P0 收敛</h2>
 * <p>见仓库 {@code docs/DAY10_P0_CLOSURE.md}：{@code pass_rate} / report 引用、剩余 regressions、修复策略与风险、门控定义。</p>
 *
 * <h2>整段墙钟上限</h2>
 * <p>本入口在独立线程执行 {@link EvalChatService#buildStubResponse}，并以 {@code app.agent.total-timeout} 作为
 * {@code Future#get} 上限；超时返回 {@code behavior=clarify}、{@code error_code=AGENT_TOTAL_TIMEOUT}，且
 * {@code meta.agent_latency_budget_exceeded=true}（见 {@link EvalChatService#buildTotalTimeoutStubResponse}）。</p>
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalChatController {

    private final EvalChatService evalChatService;
    private final AppAgentProperties appAgentProperties;
    private final ThreadPoolTaskExecutor evalChatTimeoutExecutor;

    public EvalChatController(
            EvalChatService evalChatService,
            AppAgentProperties appAgentProperties,
            @Qualifier("evalChatTimeoutExecutor") ThreadPoolTaskExecutor evalChatTimeoutExecutor
    ) {
        this.evalChatService = evalChatService;
        this.appAgentProperties = appAgentProperties;
        this.evalChatTimeoutExecutor = evalChatTimeoutExecutor;
    }

    /**
     * 评测聊天：非流式；测量从进入本方法到返回前的耗时，写入 {@code latency_ms}。
     */
    // 显式声明 UTF-8，避免部分客户端（如 PowerShell Invoke-WebRequest）按默认编码解读导致中文乱码。
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
    public EvalChatResponse chat(
            @RequestBody EvalChatRequest request,
            @RequestHeader(value = "X-Eval-Membership-Top-N", required = false) Integer xEvalMembershipTopN,
            @RequestHeader(value = "X-Eval-Token", required = false) String xEvalToken,
            @RequestHeader(value = "X-Eval-Target-Id", required = false) String xEvalTargetId,
            @RequestHeader(value = "X-Eval-Dataset-Id", required = false) String xEvalDatasetId,
            @RequestHeader(value = "X-Eval-Case-Id", required = false) String xEvalCaseId
    ) {
        long startMs = System.currentTimeMillis();
        EvalMembershipHttpContext membershipCtx = EvalMembershipHttpContext.fromHeaders(
                xEvalToken, xEvalTargetId, xEvalDatasetId, xEvalCaseId);
        Duration totalBudget = appAgentProperties.getTotalTimeout();
        long limitMs = Math.max(1L, totalBudget.toMillis());

        CompletableFuture<EvalChatResponse> future = CompletableFuture.supplyAsync(
                () -> evalChatService.buildStubResponse(request, xEvalMembershipTopN, membershipCtx),
                evalChatTimeoutExecutor
        );

        EvalChatResponse body;
        try {
            body = future.get(limitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            body = evalChatService.buildTotalTimeoutStubResponse(request, membershipCtx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            body = evalChatService.buildTotalTimeoutStubResponse(request, membershipCtx);
        } catch (ExecutionException e) {
            // 流水线内未预期异常（如检索链路故障）降级为带 error_code 的 200，而非冒泡成 500。
            // 与 TimeoutException 分支一致，走同一套 meta/latency 收尾。Error 仍向上抛（JVM 级故障不应吞）。
            Throwable c = e.getCause();
            if (c instanceof Error err) {
                throw err;
            }
            body = evalChatService.buildInternalErrorStubResponse(request, membershipCtx);
        }

        // 先走同一套 meta/capabilities 拼装，保证契约字段始终齐全；空 query 仅覆盖 answer/behavior。
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            body.setAnswer("请求体缺少非空字段 query。");
            body.setBehavior("clarify");
        }
        long latencyMs = System.currentTimeMillis() - startMs;
        body.setLatencyMs(latencyMs);
        evalChatService.applyLatencyBudgetToMeta(body, latencyMs);
        return body;
    }
}
