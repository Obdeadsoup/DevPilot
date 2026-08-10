package com.obdeadsoup.devpilot.outbox.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxHandleResult;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在一个数据库事务中完成白名单 Handler 副作用和 Outbox PROCESSED。Notification INSERT 成功但状态更新失败时，
 * 整个事务回滚；已存在的幂等 Notification 仍可让 Outbox 正常完成。
 */
@Service
public class OutboxDispatchService {

    private final ObjectMapper objectMapper;
    private final OutboxHandlerRegistry registry;
    private final OutboxStateService stateService;

    public OutboxDispatchService(
            ObjectMapper objectMapper,
            OutboxHandlerRegistry registry,
            OutboxStateService stateService) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.stateService = stateService;
    }

    @Transactional
    public void dispatch(OutboxEventEntity stored) {
        JsonNode payload = parsePayload(stored.getPayloadJson());
        OutboxEventEnvelope envelope = new OutboxEventEnvelope(
                stored.getEventKey(),
                stored.getAggregateType(),
                stored.getAggregateId(),
                stored.getEventType(),
                stored.getSchemaVersion(),
                payload,
                stored.getOccurredAt());
        OutboxHandleResult result = registry
                .require(stored.getEventType(), stored.getSchemaVersion())
                .handle(envelope);
        if (result != OutboxHandleResult.PROCESSED) {
            throw new OutboxProcessingException(
                    OutboxFailureType.INVALID_EVENT, "Outbox handler did not complete");
        }
        stateService.markProcessed(stored);
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null || !payload.isObject()) {
                throw malformed();
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw malformed();
        }
    }

    private OutboxProcessingException malformed() {
        return new OutboxProcessingException(
                OutboxFailureType.MALFORMED_PAYLOAD, "Malformed outbox payload");
    }
}
