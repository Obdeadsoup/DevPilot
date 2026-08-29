package com.obdeadsoup.devpilot.agent.sse;

import com.obdeadsoup.devpilot.agent.application.AgentRunEventPublisher;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.config.AgentRunSseProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * run-scoped 单 JVM SSE Hub。每个 run 只保留有界事件 deque；terminal 后按 TTL 清理，数据库仍是权威来源。
 */
@Component
public class AgentRunEventHub implements AgentRunEventPublisher {
    private final Map<String, RunState> states = new ConcurrentHashMap<>();
    private final AtomicLong connectionSequence = new AtomicLong();
    private final AgentRunSseProperties properties;
    private final AgentRunStreamMetrics metrics;
    private final LongFunction<SseEmitter> emitterFactory;
    private final Supplier<Instant> now;

    @Autowired
    public AgentRunEventHub(AgentRunSseProperties properties,
                            MeterRegistry meterRegistry,
                            AgentRunStreamMetrics metrics) {
        this(properties, metrics, SseEmitter::new, Instant::now);
        Gauge.builder("devpilot.agent.sse.connections", this, AgentRunEventHub::activeConnections)
                .description("当前 JVM 上活跃的 AgentRun SSE 连接数")
                .register(meterRegistry);
    }

    AgentRunEventHub(AgentRunSseProperties properties,
                     AgentRunStreamMetrics metrics,
                     LongFunction<SseEmitter> emitterFactory,
                     Supplier<Instant> now) {
        this.properties = properties;
        this.metrics = metrics;
        this.emitterFactory = emitterFactory;
        this.now = now;
    }

    @Override
    public void initialize(String runId) {
        states.compute(runId, (ignored, existing) -> {
            RunState state = existing == null ? new RunState() : existing;
            synchronized (state) {
                state.initialized = true;
            }
            return state;
        });
    }

    /** 原子完成 replay 后再进入 live，避免注册窗口丢失或先 live 后 replay。 */
    public SseEmitter register(String runId, Long lastSequence) {
        SseEmitter emitter = emitterFactory.apply(properties.timeout().toMillis());
        Connection connection = new Connection(connectionSequence.incrementAndGet(), emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(ignored -> remove(runId, emitter));

        AtomicReference<Connection> evicted = new AtomicReference<>();
        // 与 TTL cleanup 使用同一 per-key compute，避免把连接注册到刚被移除的旧 RunState。
        states.compute(runId, (ignored, existing) -> {
            RunState state = existing == null ? new RunState() : existing;
            synchronized (state) {
                boolean replayGap = hasReplayGap(state, lastSequence);
                if (replayGap && !send(runId, emitter, "replay-gap",
                        () -> SseEmitter.event().name("replay-gap")
                                .data(new AgentRunReplayGapSseData(runId, true)))) {
                    return state;
                }
                List<AgentStreamEvent> replay = state.buffer.stream()
                        .filter(event -> lastSequence == null || event.sequence() > lastSequence)
                        .toList();
                for (AgentStreamEvent event : replay) {
                    if (!sendEvent(runId, emitter, event)) {
                        return state;
                    }
                }
                boolean terminalKnown = state.buffer.stream().anyMatch(event -> event.type().isTerminal());
                if (terminalKnown) {
                    // Last-Event-ID 已经等于 terminal 时虽无需重放，也不能留下永远没有新事件的 live 连接。
                    emitter.complete();
                    return state;
                }
                if (state.connections.size() >= properties.maxConnectionsPerRun()) {
                    evicted.set(state.connections.removeFirst());
                }
                state.connections.addLast(connection);
                return state;
            }
        });
        if (evicted.get() != null) {
            evicted.get().emitter().complete();
        }
        return emitter;
    }

    @Override
    public void publish(AgentStreamEvent event) {
        RunState state = states.computeIfAbsent(event.runId(), ignored -> new RunState());
        List<SseEmitter> emitters;
        synchronized (state) {
            state.initialized = true;
            if (state.buffer.size() >= properties.replayCapacity()) {
                state.buffer.removeFirst();
            }
            state.buffer.addLast(event);
            if (event.type().isTerminal()) {
                state.terminalExpiresAt = now.get().plus(properties.terminalRetention());
            }
            emitters = state.connections.stream().map(Connection::emitter).toList();
        }
        emitters.forEach(emitter -> sendEvent(event.runId(), emitter, event));
        if (event.type().isTerminal()) {
            // Browser disconnect/流结束不是 Cancel；Python 已经完成，此处只释放 HTTP 连接。
            emitters.forEach(emitter -> {
                emitter.complete();
                remove(event.runId(), emitter);
            });
        }
    }

    public void heartbeat() {
        states.forEach((runId, state) -> snapshot(state).forEach(emitter -> send(
                runId,
                emitter,
                "heartbeat",
                () -> SseEmitter.event().name("heartbeat")
                        .data(new AgentRunHeartbeatSseData(runId)))));
        removeExpiredTerminalBuffers();
    }

    public void removeExpiredTerminalBuffers() {
        Instant current = now.get();
        states.forEach((runId, ignored) -> states.computeIfPresent(runId, (key, state) -> {
            synchronized (state) {
                if (state.terminalExpiresAt != null
                        && !state.terminalExpiresAt.isAfter(current)
                        && state.connections.isEmpty()) {
                    return null;
                }
                return state;
            }
        }));
    }

    public int activeConnections() {
        return states.values().stream().mapToInt(state -> {
            synchronized (state) {
                return state.connections.size();
            }
        }).sum();
    }

    public int activeConnections(String runId) {
        RunState state = states.get(runId);
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            return state.connections.size();
        }
    }

    private boolean hasReplayGap(RunState state, Long lastSequence) {
        if (!state.initialized) {
            return true;
        }
        if (lastSequence == null) {
            return false;
        }
        if (state.buffer.isEmpty()) {
            return lastSequence > 0;
        }
        long oldest = state.buffer.getFirst().sequence();
        long newest = state.buffer.getLast().sequence();
        return lastSequence < oldest - 1 || lastSequence > newest;
    }

    private boolean sendEvent(String runId, SseEmitter emitter, AgentStreamEvent event) {
        return send(runId, emitter, sseName(event.type()), () -> SseEmitter.event()
                .name(sseName(event.type()))
                .id(event.eventId())
                .data(AgentRunSseEventData.from(event)));
    }

    private boolean send(String runId, SseEmitter emitter, String channel,
                         Supplier<SseEmitter.SseEventBuilder> event) {
        try {
            emitter.send(event.get());
            metrics.send(channel, true);
            return true;
        } catch (IOException | IllegalStateException exception) {
            metrics.send(channel, false);
            remove(runId, emitter);
            emitter.completeWithError(exception);
            return false;
        }
    }

    private List<SseEmitter> snapshot(RunState state) {
        synchronized (state) {
            return new ArrayList<>(state.connections.stream().map(Connection::emitter).toList());
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        RunState state = states.get(runId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            state.connections.removeIf(connection -> connection.emitter() == emitter);
        }
    }

    private String sseName(AgentStreamEventType type) {
        return switch (type) {
            case RUN_STARTED -> "run-started";
            case MODEL_STEP_STARTED -> "model-step-started";
            case TOOL_STARTED -> "tool-started";
            case TOOL_COMPLETED -> "tool-completed";
            case RUN_SUCCEEDED -> "run-succeeded";
            case RUN_FAILED -> "run-failed";
            case RUN_CANCELLED -> "run-cancelled";
        };
    }

    @PreDestroy
    public void closeAll() {
        states.values().forEach(state -> snapshot(state).forEach(SseEmitter::complete));
        states.clear();
    }

    private static final class RunState {
        private final Deque<Connection> connections = new ArrayDeque<>();
        private final Deque<AgentStreamEvent> buffer = new ArrayDeque<>();
        private boolean initialized;
        private Instant terminalExpiresAt;
    }

    private record Connection(long sequence, SseEmitter emitter) {
    }
}
