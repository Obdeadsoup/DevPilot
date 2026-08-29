package com.obdeadsoup.devpilot.agent.config;

import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamingPort;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeResilienceMetrics;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.GrpcAgentRuntimeStreamingClient;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.ResilientAgentRuntimeStreamingPort;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Core→Python 的 Resilience4j 装配；使用 Semaphore Bulkhead，不切换 gRPC callback 线程。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentResilienceProperties.class)
public class AgentResilienceConfiguration {
    @Bean
    AgentRuntimeResilienceMetrics agentRuntimeResilienceMetrics(MeterRegistry registry) {
        return new AgentRuntimeResilienceMetrics(registry);
    }

    @Bean
    CircuitBreaker agentRuntimeCircuitBreaker(AgentResilienceProperties properties) {
        return CircuitBreaker.of("agentRuntime", CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.openStateDuration())
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenPermittedCalls())
                .build());
    }

    @Bean
    Bulkhead agentRuntimeBulkhead(AgentResilienceProperties properties) {
        return Bulkhead.of("agentRuntime", BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxActiveRuns())
                .maxWaitDuration(java.time.Duration.ZERO)
                .build());
    }

    @Bean
    @Primary
    AgentRuntimeStreamingPort agentRuntimeStreamingPort(
            GrpcAgentRuntimeStreamingClient client,
            CircuitBreaker agentRuntimeCircuitBreaker,
            Bulkhead agentRuntimeBulkhead,
            AgentRuntimeResilienceMetrics metrics
    ) {
        return new ResilientAgentRuntimeStreamingPort(
                client, agentRuntimeCircuitBreaker, agentRuntimeBulkhead, metrics);
    }
}
