package com.obdeadsoup.devpilot.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import com.obdeadsoup.devpilot.outbox.event.OutboxStoredSignal;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxEventMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 使用 MySQL 实现 Outbox 发布；进程内 Signal 只负责提交后的快速唤醒，不承担可靠存储。 */
@Service
public class DatabaseOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxEventMapper mapper;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final OutboxMetrics metrics;

    public DatabaseOutboxEventPublisher(
            OutboxEventMapper mapper,
            ObjectMapper objectMapper,
            ApplicationEventPublisher events,
            OutboxMetrics metrics) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.events = events;
        this.metrics = metrics;
    }

    /**
     * INSERT 必须加入 Task 当前事务；JSON 序列化失败会抛出并回滚整个业务动作。
     * 相同 eventKey 且事实完全一致视为幂等，不一致则稳定冲突。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(OutboxEventEnvelope event) {
        String payloadJson = serialize(event);
        OutboxEventEntity entity = toEntity(event, payloadJson);
        try {
            mapper.insert(entity);
            metrics.published(event.eventType(), "created");
            events.publishEvent(new OutboxStoredSignal(entity.getId()));
            return entity.getId();
        } catch (DuplicateKeyException exception) {
            OutboxEventEntity existing = mapper.findByEventKeyForUpdate(event.eventKey())
                    .orElseThrow(() -> conflict("Duplicate event key winner is not visible"));
            if (!sameFact(existing, event)) {
                throw conflict("Outbox event key points to a different fact");
            }
            metrics.deduplicated(event.eventType());
            return existing.getId();
        }
    }

    private String serialize(OutboxEventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            throw new OutboxProcessingException(
                    OutboxFailureType.INVALID_EVENT, "Outbox payload serialization failed");
        }
    }

    private OutboxEventEntity toEntity(OutboxEventEnvelope event, String payloadJson) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setEventKey(event.eventKey());
        entity.setAggregateType(event.aggregateType());
        entity.setAggregateId(event.aggregateId());
        entity.setEventType(event.eventType());
        entity.setSchemaVersion(event.schemaVersion());
        entity.setPayloadJson(payloadJson);
        entity.setOccurredAt(event.occurredAt());
        return entity;
    }

    private boolean sameFact(OutboxEventEntity existing, OutboxEventEnvelope event) {
        return existing.getAggregateType().equals(event.aggregateType())
                && existing.getAggregateId() == event.aggregateId()
                && existing.getEventType().equals(event.eventType())
                && existing.getSchemaVersion() == event.schemaVersion()
                && sameJson(existing.getPayloadJson(), event)
                && existing.getOccurredAt().equals(event.occurredAt());
    }

    private boolean sameJson(String existingPayloadJson, OutboxEventEnvelope event) {
        try {
            return objectMapper.readTree(existingPayloadJson).equals(event.payload());
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private OutboxProcessingException conflict(String message) {
        return new OutboxProcessingException(OutboxFailureType.INVALID_EVENT, message);
    }
}
