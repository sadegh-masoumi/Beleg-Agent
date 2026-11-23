package dev.belegagent.app.processing;

import dev.belegagent.app.intake.Documents;
import dev.belegagent.app.intake.Tenants;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ExtractionController {

    private final Documents documents;
    private final Extractions extractions;
    private final Tenants tenants;

    ExtractionController(Documents documents, Extractions extractions, Tenants tenants) {
        this.documents = documents;
        this.extractions = extractions;
        this.tenants = tenants;
    }

    /** Latest extraction of a document; 404 while it is still waiting or being processed. */
    @GetMapping("/api/documents/{id}/extraction")
    Extraction latest(
            @RequestHeader(name = Tenants.HEADER, required = false) String tenantHeader,
            @PathVariable UUID id) {
        documents.find(tenants.resolve(tenantHeader), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        return extractions.findLatest(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No extraction yet"));
    }
}
