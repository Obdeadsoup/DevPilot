package com.obdeadsoup.devpilot.agent.sse;

import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.config.AgentRunSseProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentRunEventHubTest {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-26T05:00:00Z"));
    private final List<CapturingEmitter> emitters = new ArrayList<>();

    @Test
    void replaysAfterLastEventIdAndSignalsGapWhenBoundedBufferDroppedHistory() {
        AgentRunEventHub hub = hub(2, 2);
        hub.initialize("run-1");
        hub.publish(event(1, AgentStreamEventType.RUN_STARTED));
        hub.publish(event(2, AgentStreamEventType.MODEL_STEP_STARTED));
        hub.publish(event(3, AgentStreamEventType.MODEL_STEP_STARTED));

        hub.register("run-1", 0L);
        assertThat(emitters.getLast().sendCount).isEqualTo(3); // replay-gap + sequence 2/3

        hub.register("run-1", 2L);
        assertThat(emitters.getLast().sendCount).isEqualTo(1);
        assertThat(hub.activeConnections("run-1")).isEqualTo(2);
    }

    @Test
    void missingJvmCacheSendsReplayGapInsteadOfPretendingRecovery() {
        AgentRunEventHub hub = hub(4, 2);

        hub.register("run-after-restart", null);

        assertThat(emitters.getLast().sendCount).isEqualTo(1);
        assertThat(hub.activeConnections("run-after-restart")).isOne();
    }

    @Test
    void connectionLimitEvictsOldestAndTerminalReplayCompletes() {
        AgentRunEventHub hub = hub(4, 1);
        hub.initialize("run-1");
        hub.register("run-1", null);
        CapturingEmitter first = emitters.getLast();
        hub.register("run-1", null);

        assertThat(first.completed).isTrue();
        assertThat(hub.activeConnections("run-1")).isOne();

        hub.publish(event(1, AgentStreamEventType.RUN_STARTED));
        hub.publish(event(2, AgentStreamEventType.RUN_SUCCEEDED));
        assertThat(hub.activeConnections("run-1")).isZero();

        hub.register("run-1", null);
        assertThat(emitters.getLast().sendCount).isEqualTo(2);
        assertThat(emitters.getLast().completed).isTrue();

        hub.register("run-1", 2L);
        assertThat(emitters.getLast().sendCount).isZero();
        assertThat(emitters.getLast().completed).isTrue();
    }

    @Test
    void heartbeatIsNotReplayedAndExpiredTerminalBufferIsRemoved() {
        AgentRunEventHub hub = hub(4, 2);
        hub.initialize("run-1");
        hub.register("run-1", null);
        hub.heartbeat();
        assertThat(emitters.getLast().sendCount).isOne();

        hub.publish(event(1, AgentStreamEventType.RUN_SUCCEEDED));
        now.set(now.get().plus(Duration.ofMinutes(6)));
        hub.removeExpiredTerminalBuffers();
        hub.register("run-1", null);

        // 过期后只剩 replay-gap；Heartbeat 从不占用 Agent sequence 或 replay deque。
        assertThat(emitters.getLast().sendCount).isOne();
    }

    @Test
    void waitingApprovalKeepsConnectionForResumedRun() {
        AgentRunEventHub hub = hub(8, 2);
        hub.initialize("run-1");
        hub.register("run-1", null);
        CapturingEmitter emitter = emitters.getLast();

        hub.publish(event(1, AgentStreamEventType.RUN_STARTED));
        hub.publish(new AgentStreamEvent("run-1:2", "run-1", 2,
                AgentStreamEventType.RUN_WAITING_APPROVAL, 0, "", "", "",
                "proposal-1", "2026-08-26T13:15:00"));

        assertThat(emitter.completed).isFalse();
        assertThat(hub.activeConnections("run-1")).isOne();
        hub.publish(event(3, AgentStreamEventType.RUN_RESUMED));
        hub.publish(event(4, AgentStreamEventType.RUN_SUCCEEDED));
        assertThat(emitter.sendCount).isEqualTo(4);
        assertThat(emitter.completed).isTrue();
    }

    private AgentRunEventHub hub(int replayCapacity, int maxConnections) {
        AgentRunSseProperties properties = new AgentRunSseProperties(
                true, Duration.ofMinutes(30), Duration.ofSeconds(20), maxConnections,
                replayCapacity, Duration.ofMinutes(5));
        return new AgentRunEventHub(properties, mock(AgentRunStreamMetrics.class), timeout -> {
            CapturingEmitter emitter = new CapturingEmitter(timeout);
            emitters.add(emitter);
            return emitter;
        }, now::get);
    }

    private AgentStreamEvent event(long sequence, AgentStreamEventType type) {
        return new AgentStreamEvent("run-1:" + sequence, "run-1", sequence, type,
                type == AgentStreamEventType.MODEL_STEP_STARTED ? 1 : 0,
                "", type == AgentStreamEventType.RUN_SUCCEEDED ? "answer" : "", "");
    }

    private static final class CapturingEmitter extends SseEmitter {
        private int sendCount;
        private boolean completed;

        private CapturingEmitter(long timeout) {
            super(timeout);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            sendCount++;
        }

        @Override
        public synchronized void complete() {
            completed = true;
            super.complete();
        }
    }
}
