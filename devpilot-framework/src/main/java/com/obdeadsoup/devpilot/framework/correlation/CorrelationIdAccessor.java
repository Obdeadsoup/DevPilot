package com.obdeadsoup.devpilot.framework.correlation;

import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 提供当前线程 Correlation ID 的受限 MDC 访问，不把它用作身份或权限依据。 */
@Component
public class CorrelationIdAccessor {

    public Optional<String> current() {
        return Optional.ofNullable(MDC.get(CorrelationIdPolicy.MDC_KEY));
    }

    public Scope open(String correlationId) {
        String previous = MDC.get(CorrelationIdPolicy.MDC_KEY);
        MDC.put(CorrelationIdPolicy.MDC_KEY, correlationId);
        return () -> restore(previous);
    }

    public void clear() {
        MDC.remove(CorrelationIdPolicy.MDC_KEY);
    }

    private void restore(String previous) {
        if (previous == null) {
            clear();
        } else {
            MDC.put(CorrelationIdPolicy.MDC_KEY, previous);
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
