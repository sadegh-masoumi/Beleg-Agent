package dev.belegagent.app.review;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.validation.ValidationResult;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Append-only store of human review decisions (GoBD: the model output is never overwritten). */
@Repository
class Reviews {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    Reviews(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Review save(UUID documentId, UUID extractionId, ReviewRequest request, ValidationResult correctedValidation) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO review (id, document_id, extraction_id, action, beleg, validation, account, reviewer, comment)
                        VALUES (:id, :documentId, :extractionId, :action, CAST(:beleg AS jsonb), CAST(:validation AS jsonb),
                                :account, :reviewer, :comment)
                        """)
                .param("id", id)
                .param("documentId", documentId)
                .param("extractionId", extractionId)
                .param("action", request.action().name())
                .param("beleg", request.beleg() == null ? null : json.writeValueAsString(request.beleg()))
                .param("validation", correctedValidation == null ? null : json.writeValueAsString(correctedValidation))
                .param("account", blankToNull(request.account()))
                .param("reviewer", request.reviewer().strip())
                .param("comment", blankToNull(request.comment()))
                .update();
        return findLatest(documentId).orElseThrow();
    }

    Optional<Review> findLatest(UUID documentId) {
        return jdbc.sql("""
                        SELECT id, document_id, extraction_id, action, beleg::text AS beleg_json,
                               validation::text AS validation_json, account, reviewer, comment, created_at
                          FROM review
                         WHERE document_id = :documentId
                         ORDER BY created_at DESC
                         LIMIT 1
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> {
                    String beleg = rs.getString("beleg_json");
                    String validation = rs.getString("validation_json");
                    return new Review(
                            rs.getObject("id", UUID.class),
                            rs.getObject("document_id", UUID.class),
                            rs.getObject("extraction_id", UUID.class),
                            ReviewAction.valueOf(rs.getString("action")),
                            beleg == null ? null : json.readValue(beleg, Beleg.class),
                            validation == null ? null : json.readValue(validation, ValidationResult.class),
                            rs.getString("account"),
                            rs.getString("reviewer"),
                            rs.getString("comment"),
                            rs.getObject("created_at", OffsetDateTime.class));
                })
                .optional();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
