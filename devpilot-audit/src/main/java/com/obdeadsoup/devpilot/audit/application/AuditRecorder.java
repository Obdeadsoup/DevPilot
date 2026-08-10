package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.AuditRecordCommand;
import org.springframework.stereotype.Component;

/** 区分成功同事务记录与失败/拒绝独立事务记录，避免调用方误用事务传播。 */
@Component
public class AuditRecorder {
    private final AuditApplicationService service;
    public AuditRecorder(AuditApplicationService service) { this.service = service; }
    public long record(AuditRecordCommand command) { return service.append(command); }
    public long recordStandalone(AuditRecordCommand command) { return service.appendIndependent(command); }
    public long recordFailure(AuditRecordCommand command) { return service.appendIndependent(command); }
}
