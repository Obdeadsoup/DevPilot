package com.obdeadsoup.devpilot.audit.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum AuditErrorCode implements ErrorCode {
    INVALID_REPLAY_REASON("AUDIT_0400", "Replay reason must contain 10 to 500 meaningful characters", HttpStatus.BAD_REQUEST),
    INVALID_DEAD_QUERY("AUDIT_0401", "Only DEAD status can be queried from this operations endpoint", HttpStatus.BAD_REQUEST),
    AUDIT_ACCESS_DENIED("AUDIT_0403", "Audit operation is not permitted in this scope", HttpStatus.FORBIDDEN),
    DEAD_EVENT_NOT_FOUND("AUDIT_0404", "Dead outbox event was not found", HttpStatus.NOT_FOUND),
    DEAD_EVENT_SCOPE_MISMATCH("AUDIT_0405", "Outbox event does not belong to the requested scope", HttpStatus.FORBIDDEN),
    AUDIT_NOT_FOUND("AUDIT_0406", "Audit log was not found", HttpStatus.NOT_FOUND),
    SYNC_RUN_NOT_FOUND("AUDIT_0407", "GitHub sync run was not found", HttpStatus.NOT_FOUND),
    DEAD_EVENT_NOT_REPLAYABLE("AUDIT_0501", "Outbox event is not replayable", HttpStatus.CONFLICT),
    DEAD_EVENT_VERSION_CONFLICT("AUDIT_0502", "Outbox event version has changed", HttpStatus.CONFLICT),
    DEAD_EVENT_ALREADY_HAS_OPEN_REPLAY("AUDIT_0503", "Outbox event already has an open replay", HttpStatus.CONFLICT),
    SYNC_RUN_NOT_DEAD("AUDIT_0504", "GitHub sync run is not DEAD", HttpStatus.CONFLICT),
    SYNC_RUN_VERSION_CONFLICT("AUDIT_0505", "GitHub sync run version has changed", HttpStatus.CONFLICT),
    SYNC_RUN_ALREADY_HAS_OPEN_REPLAY("AUDIT_0506", "GitHub sync target already has an open run", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus status;

    AuditErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
    @Override public HttpStatus status() { return status; }
}
