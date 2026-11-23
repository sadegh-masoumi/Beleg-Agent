package dev.belegagent.app.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores and loads original files. Objects are written once and never overwritten.
 */
@Component
public class DocumentStorage {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorage.class);

    private final MinioClient client;
    private final String bucket;

    public DocumentStorage(StorageProperties properties) {
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
        this.bucket = properties.bucket();
        ensureBucket();
    }

    public void put(String objectKey, byte[] content, String contentType) {
        try (InputStream in = new ByteArrayInputStream(content)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, (long) content.length, -1L)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new StorageException("Could not store " + objectKey, e);
        }
    }

    public byte[] get(String objectKey) {
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new StorageException("Could not load " + objectKey, e);
        }
    }

    /** In compose the bucket is created by minio-init; this covers tests and fresh environments. */
    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).objectLock(true).build());
                log.info("Created bucket {} with object lock", bucket);
            }
        } catch (Exception e) {
            throw new StorageException("Bucket " + bucket + " is not available", e);
        }
    }

    public static class StorageException extends RuntimeException {

        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
