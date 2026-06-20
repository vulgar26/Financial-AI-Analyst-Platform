package com.travel.ai.agent;

import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Domain-neutral entry point for the financial research agent.
 * <p>
 * The current implementation is still backed by the legacy {@link FinancialAnalystAgentImpl}
 * class to keep existing tests, routes, and frontend behavior compatible.
 */
public interface FinancialAnalystAgent {

    Flux<ServerSentEvent<String>> chat(String conversationId, String userMessage);
}

