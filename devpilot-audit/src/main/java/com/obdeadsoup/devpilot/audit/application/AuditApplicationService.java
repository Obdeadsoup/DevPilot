package com.obdeadsoup.devpilot.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.audit.domain.AuditRecordCommand;
import com.obdeadsoup.devpilot.audit.persistence.entity.AuditInsert;
import com.obdeadsoup.devpilot.audit.persistence.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;

/**
 * Audit 追加写入口。metadata 只接受低敏感白名单，禁止 Payload、Token、Secret、SQL 与堆栈进入审计库。
 */
@Service
public class AuditApplicationService {
    private static final Set<String> SAFE_METADATA = Set.of(
            "originalStatus", "newReplayId", "originalAttemptCount", "eventType",
            "syncResourceType", "bindingId", "replaySequence");
    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditApplicationService(AuditLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long append(AuditRecordCommand command) {
        AuditInsert row = new AuditInsert(command, serialize(validateMetadata(command.metadata())));
        mapper.insert(row);
        return row.getId();
    }

    /** 失败事实不能随 Replay 事务回滚；独立事务写失败时异常继续上抛，使高风险操作 fail closed。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long appendIndependent(AuditRecordCommand command) {
        AuditInsert row = new AuditInsert(command, serialize(validateMetadata(command.metadata())));
        mapper.insert(row);
        return row.getId();
    }

    private Map<String, Object> validateMetadata(Map<String, Object> metadata) {
        Map<String, Object> safe = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (!SAFE_METADATA.containsAll(safe.keySet())) {
            throw new IllegalArgumentException("Audit metadata contains a non-whitelisted key");
        }
        return safe;
    }

    private String serialize(Map<String, Object> metadata) {
        try { return objectMapper.writeValueAsString(metadata); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Audit metadata is not serializable", exception); }
    }
}
