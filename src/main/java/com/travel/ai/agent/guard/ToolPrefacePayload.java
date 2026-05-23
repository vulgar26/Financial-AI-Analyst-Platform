package com.travel.ai.agent.guard;

/**
 * 解析 {@code TravelAgent} 注入的「工具观察」块，判断 BEGIN/END 标记之间是否含有<strong>非空</strong>正文。
 * <p>
 * 用途：工具失败时仍可能带有 outcome/error 等元数据头，但 payload 为空；此时不应视为「已有工具数据」而放行 LLM 编造实时信息。
 */
// Parses tool-observation blocks emitted by the finance analyst runtime.
// The originating implementation is still the legacy-named TravelAgent class.
public final class ToolPrefacePayload {

    private static final String DATA_START = "BEGIN_TOOL_DATA\n";

    private ToolPrefacePayload() {
    }

    /**
     * @return true 当且仅当存在标准 DATA 区间且 trim 后非空
     */
    public static boolean hasSubstantiveBody(String toolPreface) {
        if (toolPreface == null || toolPreface.isBlank()) {
            return false;
        }
        int i = toolPreface.indexOf(DATA_START);
        if (i < 0) {
            return false;
        }
        int bodyStart = i + DATA_START.length();
        int end = toolPreface.indexOf("\nEND_TOOL_DATA", bodyStart);
        if (end < 0) {
            return false;
        }
        return !toolPreface.substring(bodyStart, end).trim().isEmpty();
    }
}
