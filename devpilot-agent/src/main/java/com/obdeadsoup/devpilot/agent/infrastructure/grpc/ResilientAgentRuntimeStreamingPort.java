package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamHandle;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamingPort;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对长生命周期 async gRPC 流做手工许可记账。许可直到 terminal/error/cancel 才释放，
 * 防止只统计 stub 建流瞬间而漏掉真正的远端执行时间。
 */
public final class ResilientAgentRuntimeStreamingPort implements AgentRuntimeStreamingPort {
    private final AgentRuntimeStreamingPort delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final AgentRuntimeResilienceMetrics metrics;

    public ResilientAgentRuntimeStreamingPort(AgentRuntimeStreamingPort delegate,
                                              CircuitBreaker circuitBreaker,
                                              Bulkhead bulkhead,
                                              AgentRuntimeResilienceMetrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker must not be null");
        this.bulkhead = Objects.requireNonNull(bulkhead, "bulkhead must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public AgentRuntimeStreamHandle stream(AgentRunCommand command, AgentRuntimeEventListener listener) {
        return protectedStart(listener, guarded -> delegate.stream(command, guarded));
    }

    @Override
    public AgentRuntimeStreamHandle resumeApproval(
            com.obdeadsoup.devpilot.agent.application.AgentApprovalResumeCommand command,
            AgentRuntimeEventListener listener) {
        return protectedStart(listener, guarded -> delegate.resumeApproval(command, guarded));
    }

    private AgentRuntimeStreamHandle protectedStart(
            AgentRuntimeEventListener listener, StreamStart start) {
        if (!bulkhead.tryAcquirePermission()) {
            metrics.capacityRejected();
            listener.onError(AgentRuntimeStreamFailureKind.CAPACITY_REJECTED);
            return AgentRuntimeStreamHandle.NOOP;
        }
        if (!circuitBreaker.tryAcquirePermission()) {
            bulkhead.onComplete();
            metrics.circuitCall("not_permitted");
            listener.onError(AgentRuntimeStreamFailureKind.CIRCUIT_OPEN);
            return AgentRuntimeStreamHandle.NOOP;
        }

        long startedAt = System.nanoTime();
        Lifecycle lifecycle = new Lifecycle(startedAt);
        metrics.streamStarted();
        AtomicReference<AgentRuntimeStreamHandle> delegateHandle = new AtomicReference<>(AgentRuntimeStreamHandle.NOOP);
        AgentRuntimeEventListener guarded = new AgentRuntimeEventListener() {
            @Override
            public void onEvent(AgentStreamEvent event) {
                if (event.type().isTerminal()) {
                    lifecycle.success();
                }
                listener.onEvent(event);
            }

            @Override
            public void onError(AgentRuntimeStreamFailureKind failureKind) {
                lifecycle.finish(failureKind);
                listener.onError(failureKind);
            }

            @Override
            public void onCompleted() {
                lifecycle.success();
                listener.onCompleted();
            }
        };
        try {
            delegateHandle.set(start.open(guarded));
        } catch (RuntimeException exception) {
            guarded.onError(AgentRuntimeStreamFailureKind.UNKNOWN);
        }
        return () -> {
            try {
                delegateHandle.get().cancel();
            } finally {
                // 本地 gRPC cancel 即使异常，也不能泄漏 admission permit。
                lifecycle.finish(AgentRuntimeStreamFailureKind.USER_CANCELLED);
            }
        };
    }

    @FunctionalInterface
    private interface StreamStart {
        AgentRuntimeStreamHandle open(AgentRuntimeEventListener listener);
    }

    private final class Lifecycle {
        private final long startedAt;
        private final AtomicBoolean finished = new AtomicBoolean();

        private Lifecycle(long startedAt) {
            this.startedAt = startedAt;
        }

        private void success() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            circuitBreaker.onSuccess(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            metrics.circuitCall("success");
            releaseCapacity();
        }

        private void finish(AgentRuntimeStreamFailureKind kind) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            long duration = System.nanoTime() - startedAt;
            if (countsAsDependencyFailure(kind)) {
                circuitBreaker.onError(duration, TimeUnit.NANOSECONDS, new RuntimeException(kind.name()));
                metrics.circuitCall("failure");
            } else {
                // 参数、协议与用户取消不证明依赖不可用，因此作为响应完成释放 half-open 许可。
                circuitBreaker.onSuccess(duration, TimeUnit.NANOSECONDS);
                metrics.circuitCall("ignored");
            }
            releaseCapacity();
        }

        private void releaseCapacity() {
            bulkhead.onComplete();
            metrics.streamFinished();
        }
    }

    private boolean countsAsDependencyFailure(AgentRuntimeStreamFailureKind kind) {
        return kind == AgentRuntimeStreamFailureKind.UNAVAILABLE
                || kind == AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED
                || kind == AgentRuntimeStreamFailureKind.INTERNAL
                || kind == AgentRuntimeStreamFailureKind.UNKNOWN;
    }
}
