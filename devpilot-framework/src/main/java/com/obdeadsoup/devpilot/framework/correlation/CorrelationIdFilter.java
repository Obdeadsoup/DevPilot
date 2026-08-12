package com.obdeadsoup.devpilot.framework.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在认证和业务处理前建立请求 Correlation ID，并在响应及 MDC 中保持同值。
 * Correlation ID 只关联单实例日志，不等同于跨进程 Trace，也不能替代鉴权或业务幂等键。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private final CorrelationIdPolicy policy;
    private final CorrelationIdAccessor accessor;

    public CorrelationIdFilter(CorrelationIdPolicy policy, CorrelationIdAccessor accessor) {
        this.policy = policy;
        this.accessor = accessor;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = policy.resolve(request.getHeader(CorrelationIdPolicy.HEADER_NAME));
        response.setHeader(CorrelationIdPolicy.HEADER_NAME, correlationId);
        try (CorrelationIdAccessor.Scope ignored = accessor.open(correlationId)) {
            filterChain.doFilter(request, response);
        } finally {
            // 线程池会复用 servlet 线程；finally 清理是防止并发请求串值的最后防线。
            accessor.clear();
        }
    }
}
