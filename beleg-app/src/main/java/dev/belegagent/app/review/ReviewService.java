package dev.belegagent.app.review;

import dev.belegagent.app.intake.Document;
import dev.belegagent.app.intake.DocumentStatus;
import dev.belegagent.app.intake.Documents;
import dev.belegagent.app.processing.Extraction;
import dev.belegagent.app.processing.Extractions;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.core.validation.Finding;
import dev.belegagent.core.validation.ValidationResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Human-in-the-loop decisions. A reviewer approves the agent's data or supplies a correction; corrected data is
 * validated with the same deterministic rules. Data with remaining errors can only be approved with a comment
 * (e.g. a Kleinunternehmer receipt without VAT), so every exception to the rules is explained in the record.
 */
@Service
class ReviewService {

    private static final int MAX_QUEUE = 200;

    private final Documents documents;
    private final Extractions extractions;
    private final Reviews reviews;
    private final BelegValidator validator = new BelegValidator();

    ReviewService(Documents documents, Extractions extractions, Reviews reviews) {
        this.documents = documents;
        this.extractions = extractions;
        this.reviews = reviews;
    }

    /** Documents waiting for a human, oldest first. */
    List<QueueItem> queue(String tenant, int limit) {
        return documents.findByStatus(tenant, DocumentStatus.NEEDS_REVIEW, Math.clamp(limit, 1, MAX_QUEUE)).stream()
                .map(d -> QueueItem.of(d, extractions.findChosen(d.id()).orElse(null)))
                .toList();
    }

    @Transactional
    Review review(String tenant, UUID documentId, ReviewRequest request) {
        Document document = documents.find(tenant, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        if (!document.status().reviewable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Document is " + document.status() + " and cannot be reviewed");
        }
        Extraction extraction = extractions.findChosen(documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Document has no extraction"));

        ValidationResult remaining;
        ValidationResult corrected = null;
        switch (request.action()) {
            case APPROVE -> {
                if (request.beleg() != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use action CORRECT to send corrected data");
                }
                remaining = extraction.validation() != null ? extraction.validation() : validator.validate(extraction.beleg());
            }
            case CORRECT -> {
                if (request.beleg() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Action CORRECT needs the corrected beleg");
                }
                corrected = validator.validate(request.beleg());
                remaining = corrected;
            }
            default -> throw new IllegalStateException();
        }
        if (!remaining.valid() && (request.comment() == null || request.comment().isBlank())) {
            throw new ErrorsRemainException(remaining.errors());
        }
        if (!documents.markApproved(tenant, documentId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Document was reviewed concurrently");
        }
        return reviews.save(documentId, extraction.id(), request, corrected);
    }

    Optional<Review> latest(String tenant, UUID documentId) {
        documents.find(tenant, documentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        return reviews.findLatest(documentId);
    }

    /** Validation errors remain and the reviewer gave no reason for approving anyway. */
    static class ErrorsRemainException extends RuntimeException {

        private final List<Finding> errors;

        ErrorsRemainException(List<Finding> errors) {
            super("Validation errors remain; correct the data or add a comment explaining why it is approved anyway");
            this.errors = List.copyOf(errors);
        }

        List<Finding> errors() {
            return errors;
        }
    }

    /** One entry of the review queue. */
    record QueueItem(UUID documentId, String originalFilename, OffsetDateTime createdAt, String model,
                     String sellerName, BigDecimal grossTotal, List<Finding> errors) {

        static QueueItem of(Document d, Extraction e) {
            List<Finding> errors = e == null || e.validation() == null ? List.of() : e.validation().errors();
            return new QueueItem(d.id(), d.originalFilename(), d.createdAt(), e == null ? null : e.model(),
                    e == null ? null : e.beleg().sellerName(), e == null ? null : e.beleg().grossTotal(), errors);
        }
    }
}
