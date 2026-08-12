package com.obdeadsoup.devpilot.notification.sse;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.obdeadsoup.devpilot.notification.config.NotificationSseProperties;
import com.obdeadsoup.devpilot.notification.application.NotificationMetrics;

/**
 * 按 userId 保存线程安全的多连接集合，不保存 Token 或 Principal。单用户超限时关闭最旧连接，
 * 适配多标签页和多设备；单 JVM Registry 不提供跨实例广播。
 */
@Component
public class NotificationSseRegistry {

    private final Map<Long, Deque<Connection>> connections = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final NotificationSseProperties properties;
    private final NotificationMetrics metrics;

    public NotificationSseRegistry(
            NotificationSseProperties properties,
            MeterRegistry meterRegistry,
            NotificationMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        Gauge.builder("devpilot.notification.sse.connections", this, NotificationSseRegistry::activeConnections)
                .description("当前 JVM 上活跃的 Notification SSE 连接数")
                .register(meterRegistry);
    }

    public SseEmitter register(long userId) {
        SseEmitter emitter = new SseEmitter(properties.timeout().toMillis());
        Connection connection = new Connection(sequence.incrementAndGet(), emitter);
        Deque<Connection> userConnections = connections.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        Connection evicted = null;
        synchronized (userConnections) {
            if (userConnections.size() >= properties.maxConnectionsPerUser()) {
                evicted = userConnections.removeFirst();
            }
            userConnections.addLast(connection);
        }
        if (evicted != null) {
            evicted.emitter().complete();
        }
        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(ignored -> remove(userId, emitter));
        return emitter;
    }

    public void sendConnected(long userId, SseEmitter emitter, Object data) {
        sendOne(userId, emitter, "connected", () -> SseEmitter.event().name("connected").data(data));
    }

    public void sendNotification(long userId, long notificationId, Object data) {
        forEach(userId, emitter -> sendOne(
                userId,
                emitter, "notification",
                () -> SseEmitter.event()
                        .name("notification-created")
                        .id(Long.toString(notificationId))
                        .data(data)));
    }

    public void heartbeat() {
        List<Long> userIds = List.copyOf(connections.keySet());
        for (long userId : userIds) {
            forEach(userId, emitter -> sendOne(
                    userId, emitter, "heartbeat", () -> SseEmitter.event().comment("heartbeat")));
        }
    }

    public int activeConnections() {
        int count = 0;
        for (Deque<Connection> userConnections : connections.values()) {
            synchronized (userConnections) {
                count += userConnections.size();
            }
        }
        return count;
    }

    public int activeConnections(long userId) {
        Deque<Connection> userConnections = connections.get(userId);
        if (userConnections == null) {
            return 0;
        }
        synchronized (userConnections) {
            return userConnections.size();
        }
    }

    private void forEach(long userId, java.util.function.Consumer<SseEmitter> action) {
        Deque<Connection> userConnections = connections.get(userId);
        if (userConnections == null) {
            return;
        }
        List<SseEmitter> snapshot;
        synchronized (userConnections) {
            snapshot = new ArrayList<>(userConnections.stream().map(Connection::emitter).toList());
        }
        snapshot.forEach(action);
    }

    private void sendOne(
            long userId, SseEmitter emitter, String channel,
            Supplier<SseEmitter.SseEventBuilder> event) {
        try {
            emitter.send(event.get());
            metrics.sseSend(channel, true);
        } catch (IOException | IllegalStateException exception) {
            metrics.sseSend(channel, false);
            remove(userId, emitter);
            emitter.completeWithError(exception);
        }
    }

    private void remove(long userId, SseEmitter emitter) {
        Deque<Connection> userConnections = connections.get(userId);
        if (userConnections == null) {
            return;
        }
        synchronized (userConnections) {
            userConnections.removeIf(connection -> connection.emitter() == emitter);
            if (userConnections.isEmpty()) {
                connections.remove(userId, userConnections);
            }
        }
    }

    @PreDestroy
    public void closeAll() {
        connections.values().forEach(userConnections -> {
            List<Connection> snapshot;
            synchronized (userConnections) {
                snapshot = List.copyOf(userConnections);
                userConnections.clear();
            }
            snapshot.forEach(connection -> connection.emitter().complete());
        });
        connections.clear();
    }

    private record Connection(long sequence, SseEmitter emitter) {
    }
}
