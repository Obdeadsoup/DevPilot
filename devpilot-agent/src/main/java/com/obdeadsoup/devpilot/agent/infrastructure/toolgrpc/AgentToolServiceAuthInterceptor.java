package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 最小 service identity：常量时间比较共享密钥，失败只返回 UNAUTHENTICATED。 */
public final class AgentToolServiceAuthInterceptor implements ServerInterceptor {
    public static final String HEADER_NAME = "x-devpilot-agent-service-key";
    private static final Metadata.Key<String> SERVICE_KEY =
            Metadata.Key.of(HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);

    private final byte[] expected;
    private final AgentToolGatewayMetrics metrics;

    public AgentToolServiceAuthInterceptor(String serviceKey, AgentToolGatewayMetrics metrics) {
        this.expected = serviceKey.getBytes(StandardCharsets.UTF_8);
        this.metrics = metrics;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String supplied = headers.get(SERVICE_KEY);
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0 || !MessageDigest.isEqual(expected, actual)) {
            metrics.recordAuthDenied();
            call.close(Status.UNAUTHENTICATED.withDescription("service authentication failed"),
                    new Metadata());
            return new ServerCall.Listener<>() { };
        }
        return next.startCall(call, headers);
    }
}
