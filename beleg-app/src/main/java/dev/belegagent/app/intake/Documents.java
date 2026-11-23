package dev.belegagent.app.intake;

import dev.belegagent.app.storage.DocumentStorage;
import dev.belegagent.extraction.FileKind;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Document registry: registration with content-hash idempotency, lookup, and the
 * PostgreSQL job queue used by the worker ({@code FOR UPDATE SKIP LOCKED}).
 */
@Service
public class Documents {

    private static final Logger log = LoggerFactory.getLogger(Documents.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final DocumentStorage storage;

    public Documents(JdbcClient jdbc, DocumentStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    /**
     * Stores the file and registers it. A file the tenant already uploaded is not stored or processed again;
     * the existing document is returned with {@code duplicate = true}.
     *
     * @throws UnsupportedFileException if the content is not a PDF, JPEG, PNG or WebP
     */
    public Registration register(String tenantId, String originalFilename, byte[] content) {
        if (content.length == 0) {
            throw new UnsupportedFileException("The file is empty");
        }
        FileKind kind = FileKind.detect(content)
                .orElseThrow(() -> new UnsupportedFileException("Only PDF, JPEG, PNG and WebP files are supported"));
        String sha256 = sha256Hex(content);

        Optional<Document> existing = findByContent(tenantId, sha256);
        if (existing.isPresent()) {
            return new Registration(existing.get(), true);
        }

        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String objectKey = "%s/%d/%02d/%s.%s".formatted(
                tenantId, today.getYear(), today.getMonthValue(), id, kind.extension());
        storage.put(objectKey, content, kind.mimeType());

        int inserted = jdbc.sql("""
                        INSERT INTO document (id, tenant_id, content_sha256, object_key, original_filename,
                                              content_type, size_bytes, status)
                        VALUES (:id, :tenant, :sha, :key, :filename, :type, :size, 'RECEIVED')
                        ON CONFLICT (tenant_id, content_sha256) DO NOTHING
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("sha", sha256)
                .param("key", objectKey)
                .param("filename", originalFilename)
                .param("type", kind.mimeType())
                .param("size", (long) content.length)
                .update();

        if (inserted == 0) {
            // Concurrent upload of the same file won the race; our object stays unreferenced.
            log.warn("Concurrent duplicate upload for tenant {}, orphaned object {}", tenantId, objectKey);
            return new Registration(findByContent(tenantId, sha256).orElseThrow(), true);
        }
        return new Registration(findById(id).orElseThrow(), false);
    }

    public Optional<Document> find(String tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id AND tenant_id = :tenant")
                .param("id", id)
                .param("tenant", tenantId)
                .query(Document.class)
                .optional();
    }

    /** Loads the original file. */
    public byte[] content(Document document) {
        return storage.get(document.objectKey());
    }

    /**
     * Claims the oldest waiting document for this worker. Concurrent workers skip rows locked by others,
     * so each document is claimed exactly once.
     */
    public Optional<Document> claimNext() {
        return jdbc.sql("""
                        UPDATE document
                           SET status = 'PROCESSING', attempts = attempts + 1, updated_at = now()
                         WHERE id = (SELECT id FROM document
                                      WHERE status = 'RECEIVED'
                                      ORDER BY created_at
                                      LIMIT 1
                                      FOR UPDATE SKIP LOCKED)
                        RETURNING *
                        """)
                .query(Document.class)
                .optional();
    }

    public void markExtracted(UUID id) {
        jdbc.sql("UPDATE document SET status = 'EXTRACTED', last_error = NULL, updated_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error) {
        String message = error == null ? "unknown error"
                : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        jdbc.sql("UPDATE document SET status = 'FAILED', last_error = :error, updated_at = now() WHERE id = :id")
                .param("id", id)
                .param("error", message)
                .update();
    }

    private Optional<Document> findById(UUID id) {
        return jdbc.sql("SELECT * FROM document WHERE id = :id").param("id", id).query(Document.class).optional();
    }

    private Optional<Document> findByContent(String tenantId, String sha256) {
        return jdbc.sql("SELECT * FROM document WHERE tenant_id = :tenant AND content_sha256 = :sha")
                .param("tenant", tenantId)
                .param("sha", sha256)
                .query(Document.class)
                .optional();
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Registration(Document document, boolean duplicate) {
    }

    public static class UnsupportedFileException extends RuntimeException {

        UnsupportedFileException(String message) {
            super(message);
        }
    }
}
