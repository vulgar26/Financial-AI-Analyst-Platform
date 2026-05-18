package com.travel.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主线 PLAN 阶段专用 {@link ChatClient}：不带 {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}，
 * 避免把「规划草稿」写入用户会话记忆。
 */
@Configuration
public class MainLinePlanChatClientConfig {

    @Bean
    @Qualifier("mainLinePlanChatClient")
    ChatClient mainLinePlanChatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("""
                        你是金融研究 Agent 管线的「规划-only」模块：只做意图与阶段排序，不执行检索/工具。
                        只输出一段紧凑 JSON（不要使用 markdown 代码围栏），结构严格如下：
                        {"plan_version":"v1","goal":"一句中文目标","steps":[{"step_id":"s1","stage":"RETRIEVE","instruction":"检索相关资料。"},{"step_id":"s2","stage":"WRITE","instruction":"生成回答。"}],"constraints":{"max_steps":8,"total_timeout_ms":120000,"tool_timeout_ms":3000},"notes":"规划原因"}
                        steps 必须至少包含 RETRIEVE 与 WRITE；若用户明确需要行情、股价、市场数据、新闻检索或其它外部数据，应包含 TOOL；否则可省略 TOOL。
                        若用户问题包含行情、股价、市场数据、market、quote、price、ticker、P/E、pe、成交量、volume、涨跌、估值等行情/指标解释语义：
                        - goal 使用“market_data_explain”相关描述。
                        - notes 必须包含 workflow_id=market_data_explain。
                        - steps 必须包含 TOOL 与 GUARD。
                        - TOOL step 的 instruction 应说明调用只读 market_data mock 工具；如写 tool 字段，使用 {"name":"market_data","args":{}}。
                        除 JSON 外不要输出任何字符。""")
                .build();
    }
}
