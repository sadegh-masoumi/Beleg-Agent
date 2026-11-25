package dev.belegagent.app.intake;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Upload and status endpoints.
 * Phase 1 accepts multipart uploads through the API; presigned direct-to-MinIO uploads follow in Phase 4.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

    private final Documents documents;
    private final DocumentQueue queue;
    private final Tenants tenants;

    DocumentController(Documents documents, DocumentQueue queue, Tenants tenants) {
        this.documents = documents;
        this.queue = queue;
        this.tenants = tenants;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentView> upload(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @RequestPart("file") MultipartFile file) throws IOException {
        String tenant = tenants.resolve(tenantHeader);
        Documents.Registration registration =
                documents.register(tenant, file.getOriginalFilename(), file.getBytes());
        DocumentView body = DocumentView.of(registration.document(), registration.duplicate());
        if (registration.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/documents/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    DocumentView get(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @PathVariable UUID id) {
        return documents.find(tenants.resolve(tenantHeader), id)
                .map(d -> DocumentView.of(d, null))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    }

    /** Operator action: retry a FAILED or DEAD_LETTER document with a fresh attempt budget. */
    @PostMapping("/{id}/requeue")
    DocumentView requeue(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @PathVariable UUID id) {
        String tenant = tenants.resolve(tenantHeader);
        Document document = documents.find(tenant, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!queue.requeue(tenant, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED or DEAD_LETTER documents can be requeued (status is " + document.status() + ")");
        }
        return documents.find(tenant, id).map(d -> DocumentView.of(d, null)).orElseThrow();
    }

    @ExceptionHandler(Documents.UnsupportedFileException.class)
    ProblemDetail unsupportedFile(Documents.UnsupportedFileException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DocumentView(
            UUID id,
            DocumentStatus status,
            String originalFilename,
            String contentType,
            long sizeBytes,
            String contentSha256,
            int attempts,
            String lastError,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            Boolean duplicate,
            String extraction) {

        static DocumentView of(Document d, Boolean duplicate) {
            return new DocumentView(d.id(), d.status(), d.originalFilename(), d.contentType(), d.sizeBytes(),
                    d.contentSha256(), d.attempts(), d.lastError(),
                    d.status() == DocumentStatus.RECEIVED && d.attempts() > 0 ? d.nextAttemptAt() : null,
                    d.createdAt(), d.updatedAt(), duplicate,
                    "/api/documents/" + d.id() + "/extraction");
        }
    }
}
