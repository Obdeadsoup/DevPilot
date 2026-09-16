package com.obdeadsoup.devpilot.knowledge.storage;

import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
@ConditionalOnProperty(prefix = "devpilot.knowledge", name = "storage-mode", havingValue = "minio")
public final class MinioKnowledgeObjectStorage implements KnowledgeObjectStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioKnowledgeObjectStorage(KnowledgeProperties properties) {
        this.client = MinioClient.builder()
                .endpoint(properties.minioEndpoint())
                .credentials(properties.minioAccessKey(), properties.minioSecretKey())
                .build();
        this.bucket = properties.minioBucket();
        ensureBucket();
    }

    @Override
    public void put(String objectKey, byte[] content, String contentType) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey)
                    .stream(input, content.length, -1).contentType(contentType).build());
        } catch (Exception exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (var input = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return input.readAllBytes();
        } catch (Exception exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new KnowledgeStorageException(exception);
        }
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new KnowledgeStorageException(exception);
        }
    }
}
