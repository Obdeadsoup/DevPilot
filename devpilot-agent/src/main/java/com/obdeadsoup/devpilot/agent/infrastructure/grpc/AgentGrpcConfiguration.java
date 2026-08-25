package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRuntimePort;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Java→Python gRPC 基础设施装配：一个长生命周期 Channel、轻量 Stub 与 Application Port Adapter。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentGrpcProperties.class)
public class AgentGrpcConfiguration {

    @Bean(destroyMethod = "close")
    AgentGrpcChannel agentGrpcChannel(AgentGrpcProperties properties) {
        return AgentGrpcChannel.open(properties);
    }

    @Bean
    AgentRuntimeGrpc.AgentRuntimeBlockingStub agentRuntimeBlockingStub(
            AgentGrpcChannel channel
    ) {
        return AgentRuntimeGrpc.newBlockingStub(channel.channel());
    }

    @Bean
    AgentRuntimePort agentRuntimePort(
            AgentRuntimeGrpc.AgentRuntimeBlockingStub stub,
            AgentGrpcProperties properties
    ) {
        return new GrpcAgentRuntimeClient(stub, properties.deadline());
    }
}
