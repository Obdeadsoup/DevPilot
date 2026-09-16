package com.obdeadsoup.devpilot.knowledge.storage;

import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "storage-mode", havingValue = "local", matchIfMissing = true)
public final class LocalKnowledgeObjectStorage implements KnowledgeObjectStorage {
    private final Path root;

    public LocalKnowledgeObjectStorage(KnowledgeProperties properties) {
        this.root = Path.of(properties.localDirectory()).toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        Path target = safePath(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            return Files.readAllBytes(safePath(objectKey));
        } catch (IOException exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(safePath(objectKey));
        } catch (IOException exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    private Path safePath(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("object key escapes storage root");
        }
        return target;
    }
}
