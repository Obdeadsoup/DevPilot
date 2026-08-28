package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.google.protobuf.Struct;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolApplicationService;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolCommand;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolErrorKind;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolException;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolResult;
import com.obdeadsoup.devpilot.agent.config.AgentToolGrpcProperties;
import com.obdeadsoup.devpilot.agent.contract.v1.DevPilotToolGatewayGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.ExecuteToolRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.ExecuteToolResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentToolGrpcServerLifecycleTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private AgentToolApplicationService applicationService;
    private SimpleMeterRegistry meterRegistry;
    private AgentToolGrpcServerLifecycle lifecycle;
    private ManagedChannel channel;

    @BeforeEach
    void startServer() {
        applicationService = mock(AgentToolApplicationService.class);
        meterRegistry = new SimpleMeterRegistry();
        AgentToolGrpcProperties properties = new AgentToolGrpcProperties(
                true, "127.0.0.1", 0, SECRET, 65_536, 65_536, 2, 8, Duration.ofSeconds(2));
        AgentToolGatewayMetrics metrics = new AgentToolGatewayMetrics(meterRegistry);
        DevPilotToolGatewayGrpcService service =
                new DevPilotToolGatewayGrpcService(applicationService, properties, metrics);
        lifecycle = new AgentToolGrpcServerLifecycle(
                properties, service, new AgentToolServiceAuthInterceptor(SECRET, metrics));
        lifecycle.start();
        channel = NettyChannelBuilder.forAddress("127.0.0.1", lifecycle.boundPort())
                .usePlaintext().build();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
    }

    @Test
    void missingAndWrongKeysAreUnauthenticatedAndSecretIsNeverReturned() {
        DevPilotToolGatewayGrpc.DevPilotToolGatewayBlockingStub plain =
                DevPilotToolGatewayGrpc.newBlockingStub(channel);

        assertUnauthenticated(plain);
        assertUnauthenticated(authenticated("wrong-wrong-wrong"));
        assertThat(meterRegistry.get("devpilot.agent.tool.gateway.auth.denied").counter().count())
                .isEqualTo(2);
    }

    @Test
    void correctKeyEntersApplicationAndEchoesCallId() {
        when(applicationService.execute(any())).thenReturn(new AgentToolResult(
                "result-1", "call-1", Map.of(
                "name", "DevPilot", "external_untrusted_content", true)));

        ExecuteToolResponse response = authenticated(SECRET).executeTool(request());

        assertThat(response.getToolCallId()).isEqualTo("call-1");
        assertThat(response.getResult().getFieldsMap().get("name").getStringValue())
                .isEqualTo("DevPilot");
        verify(applicationService).execute(any(AgentToolCommand.class));
    }

    @Test
    void stableApplicationFailureMapsToGrpcWithoutRawDetails() {
        when(applicationService.execute(any()))
                .thenThrow(new AgentToolException(AgentToolErrorKind.RESULT_TOO_LARGE));

        assertThatThrownBy(() -> authenticated(SECRET).executeTool(request()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, exception -> {
                    assertThat(exception.getStatus().getCode())
                            .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                    assertThat(exception.getStatus().getDescription()).isEqualTo("RESULT_TOO_LARGE");
                });
    }

    private void assertUnauthenticated(
            DevPilotToolGatewayGrpc.DevPilotToolGatewayBlockingStub stub) {
        assertThatThrownBy(() -> stub.executeTool(request()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, exception -> {
                    assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
                    assertThat(exception.getMessage()).doesNotContain(SECRET);
                });
    }

    private DevPilotToolGatewayGrpc.DevPilotToolGatewayBlockingStub authenticated(String key) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of(AgentToolServiceAuthInterceptor.HEADER_NAME,
                Metadata.ASCII_STRING_MARSHALLER), key);
        return DevPilotToolGatewayGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private ExecuteToolRequest request() {
        return ExecuteToolRequest.newBuilder()
                .setRequestId("request-1")
                .setRunId("run-1")
                .setToolCallId("call-1")
                .setToolName("project.get_summary")
                .setArguments(Struct.getDefaultInstance())
                .build();
    }
}
