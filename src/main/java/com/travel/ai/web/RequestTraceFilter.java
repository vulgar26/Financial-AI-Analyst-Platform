package com.travel.ai.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String previous = MDC.get(MDC_REQUEST_ID);
        String requestId = resolveRequestId(request);
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous != null && !previous.isBlank()) {
                MDC.put(MDC_REQUEST_ID, previous);
            } else {
                MDC.remove(MDC_REQUEST_ID);
            }
        }
    }

    public static String currentRequestIdOrNew() {
        String current = MDC.get(MDC_REQUEST_ID);
        if (current != null && !current.isBlank()) {
            return current;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID, generated);
        return generated;
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String raw = request != null ? request.getHeader(REQUEST_ID_HEADER) : null;
        String normalized = normalize(raw);
        return normalized != null ? normalized : UUID.randomUUID().toString();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(trimmed.length(), MAX_REQUEST_ID_LENGTH));
        for (int i = 0; i < trimmed.length() && sb.length() < MAX_REQUEST_ID_LENGTH; i++) {
            char c = trimmed.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
