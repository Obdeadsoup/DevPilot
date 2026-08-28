package com.obdeadsoup.devpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Python→Java Tool gRPC Server 的网络、线程和消息硬边界；service key 永不进入 toString。 */
@ConfigurationProperties("devpilot.agent.tool-grpc")
public record AgentToolGrpcProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("127.0.0.1") String host,
        @DefaultValue("50052") int port,
        @DefaultValue("") String serviceKey,
        @DefaultValue("65536") int maxInboundMessageSize,
        @DefaultValue("65536") int maxResultBytes,
        @DefaultValue("4") int workerThreads,
        @DefaultValue("64") int queueCapacity,
        @DefaultValue("5s") Duration shutdownGrace
) {
    public AgentToolGrpcProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("tool grpc host must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("tool grpc port must be between 0 and 65535");
        }
        if (maxInboundMessageSize < 1 || maxResultBytes < 1
                || workerThreads < 1 || queueCapacity < 1
                || shutdownGrace == null || shutdownGrace.isNegative() || shutdownGrace.isZero()) {
            throw new IllegalArgumentException("tool grpc limits must be positive");
        }
        if (enabled && (serviceKey == null || serviceKey.length() < 16)) {
            throw new IllegalArgumentException("enabled tool grpc requires a service key of at least 16 characters");
        }
        serviceKey = serviceKey == null ? "" : serviceKey;
    }

    @Override
    public String toString() {
        return "AgentToolGrpcProperties[enabled=" + enabled + ", host=" + host + ", port=" + port
                + ", serviceKey=<redacted>, maxInboundMessageSize=" + maxInboundMessageSize
                + ", maxResultBytes=" + maxResultBytes + ", workerThreads=" + workerThreads
                + ", queueCapacity=" + queueCapacity + ", shutdownGrace=" + shutdownGrace + "]";
    }
}
