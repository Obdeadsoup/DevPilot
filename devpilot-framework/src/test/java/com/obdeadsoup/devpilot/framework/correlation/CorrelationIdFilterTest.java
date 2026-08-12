package com.obdeadsoup.devpilot.framework.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {
    private final CorrelationIdPolicy policy = new CorrelationIdPolicy();
    private final CorrelationIdAccessor accessor = new CorrelationIdAccessor();
    private final CorrelationIdFilter filter = new CorrelationIdFilter(policy, accessor);

    @AfterEach void clear() { accessor.clear(); }

    @Test
    void generatesForMissingInvalidAndOverlongHeadersAndReturnsResponseHeader() throws Exception {
        for (String value : new String[] {null, "bad value!", "x".repeat(65)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (value != null) request.addHeader(CorrelationIdPolicy.HEADER_NAME, value);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) ->
                    assertThat(accessor.current()).isPresent());
            assertThat(response.getHeader(CorrelationIdPolicy.HEADER_NAME))
                    .matches("[A-Za-z0-9._-]{8,64}");
            assertThat(accessor.current()).isEmpty();
        }
    }

    @Test
    void reusesSafeHeaderAndCleansMdcEvenWhenRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdPolicy.HEADER_NAME, "client-request_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            assertThat(accessor.current()).contains("client-request_123");
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(response.getHeader(CorrelationIdPolicy.HEADER_NAME)).isEqualTo("client-request_123");
        assertThat(accessor.current()).isEmpty();
    }

    @Test
    void concurrentRequestsDoNotShareValues() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> invoke("request-one", start, seen));
            var second = executor.submit(() -> invoke("request-two", start, seen));
            start.countDown();
            first.get(); second.get();
        }
        assertThat(seen).containsExactlyInAnyOrder("request-one", "request-two");
    }

    @Test
    void taskDecoratorPropagatesOnlyCorrelationAndCleansWorker() {
        CorrelationIdTaskDecorator decorator = new CorrelationIdTaskDecorator(policy, accessor);
        Runnable task;
        try (var ignored = accessor.open("parent-request")) {
            task = decorator.decorate(() -> assertThat(accessor.current()).contains("parent-request"));
        }
        task.run();
        assertThat(accessor.current()).isEmpty();

        Runnable schedulerTask = decorator.decorate(() -> assertThat(accessor.current()).isPresent());
        schedulerTask.run();
        assertThat(accessor.current()).isEmpty();

        try (var ignored = accessor.open("request-must-not-cross-durable-boundary")) {
            Runnable durable = decorator.decorateFresh(() ->
                    assertThat(accessor.current()).isNotEqualTo(java.util.Optional.of(
                            "request-must-not-cross-durable-boundary")));
            durable.run();
        }
    }

    private Void invoke(String value, CountDownLatch start, Set<String> seen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdPolicy.HEADER_NAME, value);
        start.await();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.add(accessor.current().orElseThrow()));
        return null;
    }
}
