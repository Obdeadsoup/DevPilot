package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRuntimePort;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancellationPort;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGrpcConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentGrpcConfiguration.class)
            .withPropertyValues(
                    "devpilot.agent.grpc.host=127.0.0.1",
                    "devpilot.agent.grpc.port=50051",
                    "devpilot.agent.grpc.deadline=30s",
                    "devpilot.agent.grpc.stream-deadline=10m",
                    "devpilot.agent.grpc.cancel-deadline=3s",
                    "devpilot.agent.grpc.plaintext=true"
            );

    @Test
    void createsOneReusableChannelAndClosesItWithSpringContext() {
        AtomicReference<AgentGrpcChannel> channelReference = new AtomicReference<>();

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentGrpcChannel.class);
            assertThat(context).hasSingleBean(AgentRuntimeGrpc.AgentRuntimeBlockingStub.class);
            assertThat(context).hasSingleBean(AgentRuntimeGrpc.AgentRuntimeStub.class);
            assertThat(context).hasSingleBean(AgentRuntimePort.class);
            assertThat(context).hasSingleBean(GrpcAgentRuntimeStreamingClient.class);
            assertThat(context).hasSingleBean(AgentRuntimeCancellationPort.class);
            AgentGrpcChannel channel = context.getBean(AgentGrpcChannel.class);
            channelReference.set(channel);
            assertThat(channel.channel().isShutdown()).isFalse();
        });

        assertThat(channelReference.get().channel().isShutdown()).isTrue();
    }
}
