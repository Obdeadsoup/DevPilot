package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 进程级复用的 ManagedChannel 生命周期包装器。
 *
 * <p>Channel 在 Spring 启动时创建，所有 Stub/RPC 复用；容器关闭时先 graceful shutdown，超时才强制终止。</p>
 */
public final class AgentGrpcChannel implements AutoCloseable {

    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final ManagedChannel channel;

    AgentGrpcChannel(ManagedChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
    }

    public static AgentGrpcChannel open(AgentGrpcProperties properties) {
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(properties.host(), properties.port());
        if (properties.plaintext()) {
            // plaintext 仅为本地联调；生产 TLS 与服务身份留待专门安全设计。
            builder.usePlaintext();
        } else {
            builder.useTransportSecurity();
        }
        return new AgentGrpcChannel(builder.build());
    }

    ManagedChannel channel() {
        return channel;
    }

    @Override
    public synchronized void close() {
        if (channel.isShutdown()) {
            return;
        }
        channel.shutdown();
        try {
            if (!channel.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
