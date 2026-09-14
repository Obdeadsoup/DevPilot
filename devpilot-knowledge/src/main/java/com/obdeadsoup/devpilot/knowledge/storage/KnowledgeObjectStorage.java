package com.obdeadsoup.devpilot.knowledge.storage;

public interface KnowledgeObjectStorage {
    void put(String objectKey, byte[] content, String contentType);
    byte[] get(String objectKey);
    void delete(String objectKey);
}
