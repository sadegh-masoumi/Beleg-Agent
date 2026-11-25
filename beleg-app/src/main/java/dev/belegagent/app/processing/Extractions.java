package dev.belegagent.app.processing;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.accounting.AccountSuggestion;
import dev.belegagent.core.validation.ValidationResult;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.agent.AgentResult;
import dev.belegagent.extraction.agent.Attempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Append-only store of agent runs and their extraction attempts. Nothing is updated or deleted (GoBD). */
@Repository
public class Extractions {

    private static final String EXTRACTION_COLUMNS = """
            id, document_id, run_id, source, attempt, chosen, model, beleg::text AS beleg_json,
            validation::text AS validation_json, input_tokens, output_tokens, thinking_tokens, latency_ms,
            pages_sent, total_pages, created_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Extractions(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Stores one run with all attempts; call inside the transaction that updates the document status. */
    UUID saveRun(UUID documentId, AgentResult result) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO processing_run (id, document_id, decision, account, notes)
                        VALUES (:id, :documentId, :decision, CAST(:account AS jsonb), CAST(:notes AS jsonb))
                        """)
                .param("id", runId)
                .param("documentId", documentId)
                .param("decision", result.decision().name())
                .param("account", json.writeValueAsString(result.account()))
                .param("notes", json.writeValueAsString(result.notes()))
                .update();
        List<Attempt> attempts = result.attempts();
        for (int i = 0; i < attempts.size(); i++) {
            saveAttempt(documentId, runId, i + 1, i == result.chosen(), attempts.get(i));
        }
        return runId;
    }

    private void saveAttempt(UUID documentId, UUID runId, int number, boolean chosen, Attempt attempt) {
        ExtractionOutcome outcome = attempt.outcome();
        Beleg beleg = outcome.beleg();
        jdbc.sql("""
                        INSERT INTO extraction (id, document_id, run_id, source, attempt, chosen, valid, validation, model,
                                                raw_response, beleg, seller_name, issue_date, gross_total, currency,
                                                input_tokens, output_tokens, thinking_tokens, latency_ms, pages_sent, total_pages)
                        VALUES (:id, :documentId, :runId, :source, :attempt, :chosen, :valid, CAST(:validation AS jsonb), :model,
                                CAST(:raw AS jsonb), CAST(:beleg AS jsonb), :seller, :issueDate, :gross, :currency,
                                :inputTokens, :outputTokens, :thinkingTokens, :latencyMs, :pagesSent, :totalPages)
                        """)
                .param("id", UUID.randomUUID())
                .param("documentId", documentId)
                .param("runId", runId)
                .param("source", attempt.source().name())
                .param("attempt", number)
                .param("chosen", chosen)
                .param("valid", attempt.validation().valid())
                .param("validation", json.writeValueAsString(attempt.validation()))
                .param("model", outcome.model())
                .param("raw", outcome.rawResponse())
                .param("beleg", json.writeValueAsString(beleg))
                .param("seller", beleg.sellerName())
                .param("issueDate", beleg.issueDate())
                .param("gross", beleg.grossTotal())
                .param("currency", beleg.currency())
                .param("inputTokens", outcome.inputTokens())
                .param("outputTokens", outcome.outputTokens())
                .param("thinkingTokens", outcome.thinkingTokens())
                .param("latencyMs", outcome.latency().toMillis())
                .param("pagesSent", outcome.pagesSent())
                .param("totalPages", outcome.totalPages())
                .update();
    }

    /** The attempt whose data the agent used in the latest run. */
    public Optional<Extraction> findChosen(UUID documentId) {
        return jdbc.sql("SELECT " + EXTRACTION_COLUMNS + """
                          FROM extraction
                         WHERE document_id = :documentId AND chosen
                         ORDER BY created_at DESC, attempt DESC
                         LIMIT 1
                        """)
                .param("documentId", documentId)
                .query(this::extraction)
                .optional();
    }

    /** Latest agent run with all attempts; empty for documents processed before Phase 3. */
    public Optional<ProcessingRun> findLatestRun(UUID documentId) {
        return jdbc.sql("""
                        SELECT id, document_id, decision, account::text AS account_json, notes::text AS notes_json, created_at
                          FROM processing_run
                         WHERE document_id = :documentId
                         ORDER BY created_at DESC
                         LIMIT 1
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> {
                    UUID runId = rs.getObject("id", UUID.class);
                    return new ProcessingRun(
                            runId,
                            rs.getObject("document_id", UUID.class),
                            rs.getString("decision"),
                            json.readValue(rs.getString("account_json"), AccountSuggestion.class),
                            json.readValue(rs.getString("notes_json"), new TypeReference<List<String>>() {
                            }),
                            List.of(),
                            rs.getObject("created_at", OffsetDateTime.class));
                })
                .optional()
                .map(run -> new ProcessingRun(run.id(), run.documentId(), run.decision(), run.account(), run.notes(),
                        attempts(run.id()), run.createdAt()));
    }

    private List<Extraction> attempts(UUID runId) {
        return jdbc.sql("SELECT " + EXTRACTION_COLUMNS + " FROM extraction WHERE run_id = :runId ORDER BY attempt")
                .param("runId", runId)
                .query(this::extraction)
                .list();
    }

    private Extraction extraction(ResultSet rs, int rowNum) throws SQLException {
        String validation = rs.getString("validation_json");
        return new Extraction(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("source"),
                rs.getInt("attempt"),
                rs.getBoolean("chosen"),
                rs.getString("model"),
                json.readValue(rs.getString("beleg_json"), Beleg.class),
                validation == null ? null : json.readValue(validation, ValidationResult.class),
                rs.getObject("input_tokens", Integer.class),
                rs.getObject("output_tokens", Integer.class),
                rs.getObject("thinking_tokens", Integer.class),
                rs.getLong("latency_ms"),
                rs.getInt("pages_sent"),
                rs.getInt("total_pages"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
