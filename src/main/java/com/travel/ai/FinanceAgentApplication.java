package com.travel.ai;

import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.config.AppConversationProperties;
import com.travel.ai.config.AppEvalProperties;
import com.travel.ai.config.AppFeedbackProperties;
import com.travel.ai.config.AppMemoryProperties;
import com.travel.ai.finance.fundamentals.FmpProperties;
import com.travel.ai.task.AgentTaskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        AppAgentProperties.class,
        AgentTaskProperties.class,
        AppEvalProperties.class,
        AppConversationProperties.class,
        AppMemoryProperties.class,
        AppFeedbackProperties.class,
        FmpProperties.class
})
public class FinanceAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceAgentApplication.class, args);
    }
}
