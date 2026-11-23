package dev.belegagent.app;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Worker role end to end (upload → queue → extraction → result) with a fake model. */
@SpringBootTest(properties = "beleg.worker.poll-interval-ms=100")
@AutoConfigureMockMvc
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class ExtractionPipelineTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    BelegExtractor extractor;

    @Test
    void uploadedDocumentIsExtractedAndStored() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-ok");
        given(extractor.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome());

        String id = upload(png);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/documents/{id}", id)).andExpect(jsonPath("$.status").value("EXTRACTED")));

        mvc.perform(get("/api/documents/{id}/extraction", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("fake-model"))
                .andExpect(jsonPath("$.beleg.sellerName").value("Bäckerei Schmidt"))
                .andExpect(jsonPath("$.beleg.issueDate").value("2026-11-03"))
                .andExpect(jsonPath("$.beleg.grossTotal").value(4.15))
                .andExpect(jsonPath("$.beleg.positions.length()").value(2))
                .andExpect(jsonPath("$.inputTokens").value(1100));
    }

    @Test
    void failedExtractionMarksDocumentFailed() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-fail");
        given(extractor.extract(eq(png), any())).willThrow(new ExtractionException("model unavailable"));

        String id = upload(png);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/documents/{id}", id))
                        .andExpect(jsonPath("$.status").value("FAILED"))
                        .andExpect(jsonPath("$.lastError").value("model unavailable"))
                        .andExpect(jsonPath("$.attempts").value(1)));
    }

    private String upload(byte[] content) throws Exception {
        String body = mvc.perform(multipart("/api/documents").file(DocumentApiTests.file("bon.png", content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static ExtractionOutcome outcome() {
        Beleg beleg = new Beleg(DocumentKind.RECEIPT, "Bäckerei Schmidt", null, null, null, null, "0815",
                LocalDate.of(2026, 11, 3), null, "EUR",
                List.of(new Position("Brötchen", new BigDecimal("3"), new BigDecimal("0.45"),
                                new BigDecimal("1.35"), new BigDecimal("7")),
                        new Position("Kaffee", BigDecimal.ONE, new BigDecimal("2.80"),
                                new BigDecimal("2.80"), new BigDecimal("19"))),
                List.of(new TaxLine(new BigDecimal("7"), new BigDecimal("1.26"), new BigDecimal("0.09"),
                                new BigDecimal("1.35")),
                        new TaxLine(new BigDecimal("19"), new BigDecimal("2.35"), new BigDecimal("0.45"),
                                new BigDecimal("2.80"))),
                null, null, new BigDecimal("4.15"), null, "BAR");
        return new ExtractionOutcome(beleg, "{\"sellerName\":\"Bäckerei Schmidt\"}", "fake-model", 1100, 400, null,
                Duration.ofMillis(1234), 1, 1);
    }
}
