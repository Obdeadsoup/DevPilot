package com.obdeadsoup.devpilot.knowledge.storage;

public final class KnowledgeStorageException extends RuntimeException {
    public KnowledgeStorageException(Throwable cause) {
        super("knowledge object storage operation failed", cause);
    }
}
