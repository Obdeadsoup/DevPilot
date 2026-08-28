package com.obdeadsoup.devpilot.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

class GatewayRequestIdFilterTest {

    private final GatewayRequestIdFilter filter = new GatewayRequestIdFilter(new CorrelationIdPolicy());

    @Test
    void preservesValidRequestIdAndNormalizesBothHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(GatewayRequestIdFilter.REQUEST_ID_HEADER, "request-id-123")
                        .header(CorrelationIdPolicy.HEADER_NAME, "different-id-456"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, current -> {
                    forwarded.set(current);
                    return current.getResponse().setComplete();
                }))
                .verifyComplete();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(GatewayRequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo("request-id-123");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdPolicy.HEADER_NAME))
                .isEqualTo("request-id-123");
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayRequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo("request-id-123");
    }

    @Test
    void reusesValidCoreCorrelationIdWhenRequestIdIsInvalid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(GatewayRequestIdFilter.REQUEST_ID_HEADER, "bad id")
                        .header(CorrelationIdPolicy.HEADER_NAME, "correlation-123"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, current -> {
                    forwarded.set(current);
                    return current.getResponse().setComplete();
                }))
                .verifyComplete();

        assertThat(forwarded.get().getRequest().getHeaders().getFirst(GatewayRequestIdFilter.REQUEST_ID_HEADER))
                .isEqualTo("correlation-123");
        assertThat(forwarded.get().getRequest().getHeaders().getFirst(CorrelationIdPolicy.HEADER_NAME))
                .isEqualTo("correlation-123");
    }

    @Test
    void generatesSafeIdWhenExternalValuesAreMissingOrInvalid() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/test")
                        .header(CorrelationIdPolicy.HEADER_NAME, "x".repeat(65)));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, current -> {
                    forwarded.set(current);
                    return current.getResponse().setComplete();
                }))
                .verifyComplete();

        String generated = forwarded.get().getRequest().getHeaders()
                .getFirst(GatewayRequestIdFilter.REQUEST_ID_HEADER);
        assertThat(generated).matches("[0-9a-f-]{36}");
        assertThat(new CorrelationIdPolicy().isValid(generated)).isTrue();
    }
}
