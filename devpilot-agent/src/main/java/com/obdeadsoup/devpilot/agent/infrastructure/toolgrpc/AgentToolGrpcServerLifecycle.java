package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.obdeadsoup.devpilot.agent.config.AgentToolGrpcProperties;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 50052 Tool gRPC Server 生命周期；有界线程池只执行短 read-only Application Query。 */
public final class AgentToolGrpcServerLifecycle implements SmartLifecycle {
    private final AgentToolGrpcProperties properties;
    private final DevPilotToolGatewayGrpcService service;
    private final AgentToolServiceAuthInterceptor interceptor;
    private volatile boolean running;
    private Server server;
    private ThreadPoolExecutor executor;

    public AgentToolGrpcServerLifecycle(AgentToolGrpcProperties properties,
                                        DevPilotToolGatewayGrpcService service,
                                        AgentToolServiceAuthInterceptor interceptor) {
        this.properties = properties;
        this.service = service;
        this.interceptor = interceptor;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            running = true;
            return;
        }
        executor = new ThreadPoolExecutor(
                properties.workerThreads(), properties.workerThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), threadFactory(),
                new ThreadPoolExecutor.AbortPolicy());
        try {
            server = NettyServerBuilder
                    .forAddress(new InetSocketAddress(properties.host(), properties.port()))
                    .maxInboundMessageSize(properties.maxInboundMessageSize())
                    .executor(executor)
                    .addService(ServerInterceptors.intercept(service, interceptor))
                    .build()
                    .start();
            running = true;
        } catch (IOException | RuntimeException exception) {
            executor.shutdownNow();
            throw new IllegalStateException("failed to start Agent Tool gRPC server", exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (server != null) {
            server.shutdown();
            try {
                if (!server.awaitTermination(properties.shutdownGrace().toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int boundPort() {
        return server == null ? -1 : server.getPort();
    }

    private ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "agent-tool-grpc-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
