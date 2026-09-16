package com.obdeadsoup.devpilot.knowledge.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum KnowledgeErrorCode implements ErrorCode {
    DOCUMENT_NOT_FOUND("KNOWLEDGE_0404", "Knowledge document not found", HttpStatus.NOT_FOUND),
    INVALID_DOCUMENT("KNOWLEDGE_0400", "Knowledge document is invalid or unsupported", HttpStatus.BAD_REQUEST),
    DOCUMENT_TOO_LARGE("KNOWLEDGE_0413", "Knowledge document exceeds upload limit", HttpStatus.PAYLOAD_TOO_LARGE),
    INGESTION_CONFLICT("KNOWLEDGE_0501", "Knowledge document state changed concurrently", HttpStatus.CONFLICT),
    INGESTION_FAILED("KNOWLEDGE_0502", "Knowledge document ingestion failed", HttpStatus.UNPROCESSABLE_ENTITY);

    private final String code;
    private final String message;
    private final HttpStatus status;

    KnowledgeErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override public String code() { return code; }
    @Override public String message() { return message; }
    @Override public HttpStatus status() { return status; }
}
