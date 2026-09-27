package com.invoicematch.core.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the request trace id before the security chain runs, echoes it on
 * the response, and exposes it to audit and logging. It runs at the highest
 * precedence so even a 401/403 response carries the trace id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = TraceId.resolve(request.getHeader(TraceId.HEADER));
        TraceContext.set(traceId);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TraceId.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
