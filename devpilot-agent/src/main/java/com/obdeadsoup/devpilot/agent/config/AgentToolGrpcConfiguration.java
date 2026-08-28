package com.obdeadsoup.devpilot.agent.config;

import com.obdeadsoup.devpilot.agent.application.tool.AgentToolResultSizePolicy;
import com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc.AgentToolGatewayMetrics;
import com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc.AgentToolGrpcServerLifecycle;
import com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc.AgentToolServiceAuthInterceptor;
import com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc.DevPilotToolGatewayGrpcService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Python→Java Tool Gateway 的 service identity、消息大小和 Server 生命周期装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentToolGrpcProperties.class)
public class AgentToolGrpcConfiguration {
    @Bean
    AgentToolResultSizePolicy agentToolResultSizePolicy(AgentToolGrpcProperties properties) {
        return new AgentToolResultSizePolicy(properties.maxResultBytes());
    }

    @Bean
    AgentToolGatewayMetrics agentToolGatewayMetrics(MeterRegistry meterRegistry) {
        return new AgentToolGatewayMetrics(meterRegistry);
    }

    @Bean
    AgentToolServiceAuthInterceptor agentToolServiceAuthInterceptor(
            AgentToolGrpcProperties properties, AgentToolGatewayMetrics metrics) {
        return new AgentToolServiceAuthInterceptor(properties.serviceKey(), metrics);
    }

    @Bean
    AgentToolGrpcServerLifecycle agentToolGrpcServerLifecycle(
            AgentToolGrpcProperties properties,
            DevPilotToolGatewayGrpcService service,
            AgentToolServiceAuthInterceptor interceptor) {
        return new AgentToolGrpcServerLifecycle(properties, service, interceptor);
    }
}
