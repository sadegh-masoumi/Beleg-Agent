package dev.belegagent.app.processing;

import dev.belegagent.core.Beleg;
import dev.belegagent.extraction.ExtractionOutcome;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Append-only store of model results. */
@Repository
public class Extractions {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Extractions(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void save(UUID documentId, ExtractionOutcome outcome) {
        Beleg beleg = outcome.beleg();
        jdbc.sql("""
                        INSERT INTO extraction (id, document_id, model, raw_response, beleg, seller_name, issue_date,
                                                gross_total, currency, input_tokens, output_tokens, thinking_tokens, latency_ms,
                                                pages_sent, total_pages)
                        VALUES (:id, :documentId, :model, CAST(:raw AS jsonb), CAST(:beleg AS jsonb), :seller,
                                :issueDate, :gross, :currency, :inputTokens, :outputTokens, :thinkingTokens, :latencyMs,
                                :pagesSent, :totalPages)
                        """)
                .param("id", UUID.randomUUID())
                .param("documentId", documentId)
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

    public Optional<Extraction> findLatest(UUID documentId) {
        return jdbc.sql("""
                        SELECT id, document_id, model, beleg::text AS beleg_json, input_tokens, output_tokens, thinking_tokens,
                               latency_ms, pages_sent, total_pages, created_at
                          FROM extraction
                         WHERE document_id = :documentId
                         ORDER BY created_at DESC
                         LIMIT 1
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> new Extraction(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        rs.getString("model"),
                        json.readValue(rs.getString("beleg_json"), Beleg.class),
                        rs.getObject("input_tokens", Integer.class),
                        rs.getObject("output_tokens", Integer.class),
                        rs.getObject("thinking_tokens", Integer.class),
                        rs.getLong("latency_ms"),
                        rs.getInt("pages_sent"),
                        rs.getInt("total_pages"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class)))
                .optional();
    }
}
