package dev.belegagent.app.review;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.validation.ValidationResult;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One human decision (row of table {@code review}).
 *
 * @param extractionId the model extraction that was reviewed (unchanged)
 * @param beleg        corrected data for CORRECT, {@code null} for APPROVE
 * @param validation   validation of the corrected data for CORRECT, {@code null} for APPROVE
 */
public record Review(
        UUID id,
        UUID documentId,
        UUID extractionId,
        ReviewAction action,
        Beleg beleg,
        ValidationResult validation,
        String account,
        String reviewer,
        String comment,
        OffsetDateTime createdAt) {
}
