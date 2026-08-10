package com.obdeadsoup.devpilot.audit.persistence.entity;

import com.obdeadsoup.devpilot.audit.domain.AuditRecordCommand;

public class AuditInsert {
    private Long id;
    private final AuditRecordCommand command;
    private final String metadataJson;

    public AuditInsert(AuditRecordCommand command, String metadataJson) {
        this.command = command;
        this.metadataJson = metadataJson;
    }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public AuditRecordCommand getCommand() { return command; }
    public String getMetadataJson() { return metadataJson; }
}
