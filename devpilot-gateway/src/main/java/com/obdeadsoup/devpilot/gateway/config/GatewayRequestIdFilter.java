package com.obdeadsoup.devpilot.gateway.config;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 在最外层生成或接纳安全 Request ID，并把同一个值写入 Core 已使用的 Correlation ID。
 * 这保证浏览器、Gateway 与 Core 可用一个标识串联日志，而不把请求标识误作认证凭据。
 */
@Component
public class GatewayRequestIdFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final CorrelationIdPolicy policy;

    public GatewayRequestIdFilter(CorrelationIdPolicy policy) {
        this.policy = policy;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = resolve(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(REQUEST_ID_HEADER, requestId);
                    headers.set(CorrelationIdPolicy.HEADER_NAME, requestId);
                })
                .build();
        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);
        exchange.getResponse().getHeaders().set(CorrelationIdPolicy.HEADER_NAME, requestId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    private String resolve(ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (policy.isValid(requestId)) {
            return requestId;
        }
        String correlationId = exchange.getRequest().getHeaders().getFirst(CorrelationIdPolicy.HEADER_NAME);
        return policy.resolve(correlationId);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
