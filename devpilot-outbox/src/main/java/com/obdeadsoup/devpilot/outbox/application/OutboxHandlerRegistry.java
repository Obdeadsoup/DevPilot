package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.domain.OutboxEventHandler;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 启动时建立 eventType + schemaVersion 白名单；重复 Handler 注册立即使启动失败。 */
@Component
public class OutboxHandlerRegistry {

    private final Map<HandlerKey, OutboxEventHandler> handlers;
    private final Map<String, Boolean> knownEventTypes;

    public OutboxHandlerRegistry(List<OutboxEventHandler> registeredHandlers) {
        Map<HandlerKey, OutboxEventHandler> byKey = new HashMap<>();
        Map<String, Boolean> eventTypes = new HashMap<>();
        for (OutboxEventHandler handler : registeredHandlers) {
            HandlerKey key = new HandlerKey(handler.supportedEventType(), handler.supportedSchemaVersion());
            if (byKey.putIfAbsent(key, handler) != null) {
                throw new IllegalStateException("Duplicate outbox handler: " + key);
            }
            eventTypes.put(handler.supportedEventType(), Boolean.TRUE);
        }
        this.handlers = Map.copyOf(byKey);
        this.knownEventTypes = Map.copyOf(eventTypes);
    }

    public OutboxEventHandler require(String eventType, int schemaVersion) {
        OutboxEventHandler handler = handlers.get(new HandlerKey(eventType, schemaVersion));
        if (handler != null) {
            return handler;
        }
        if (knownEventTypes.containsKey(eventType)) {
            throw new OutboxProcessingException(
                    OutboxFailureType.UNSUPPORTED_SCHEMA, "Unsupported outbox schema version");
        }
        throw new OutboxProcessingException(
                OutboxFailureType.UNKNOWN_EVENT_TYPE, "Unknown outbox event type");
    }

    private record HandlerKey(String eventType, int schemaVersion) {
    }
}
