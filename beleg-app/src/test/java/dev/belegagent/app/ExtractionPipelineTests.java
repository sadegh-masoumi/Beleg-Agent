package dev.belegagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Worker role end to end (upload → queue → agent → result → review, retry, dead-letter) with fake models. */
@SpringBootTest(properties = {
        "beleg.worker.poll-interval-ms=100",
        "beleg.worker.concurrency=4",
        "beleg.worker.max-attempts=3",
        "beleg.worker.retry-base-delay=50ms",
        "beleg.worker.retry-max-delay=200ms"})
@AutoConfigureMockMvc
@ActiveProfiles("worker")
@Import(TestcontainersConfiguration.class)
class ExtractionPipelineTests {

    @Autowired
    MockMvc mvc;

    @MockitoBean(name = "primaryExtractor")
    BelegExtractor primary;

    @MockitoBean(name = "correctionExtractor")
    BelegExtractor corrector;

    @Test
    void validReceiptIsAcceptedWithoutCorrection() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-ok");
        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.45")));

        String id = upload(png);
        awaitStatus(id, "ACCEPTED");

        mvc.perform(get("/api/documents/{id}/extraction", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("cheap-model"))
                .andExpect(jsonPath("$.source").value("MODEL"))
                .andExpect(jsonPath("$.chosen").value(true))
                .andExpect(jsonPath("$.validation.ruleSet").value("SMALL_AMOUNT"))
                .andExpect(jsonPath("$.validation.findings.length()").value(0))
                .andExpect(jsonPath("$.beleg.sellerName").value("Bäckerei Schmidt"))
                .andExpect(jsonPath("$.beleg.issueDate").value("2026-11-03"))
                .andExpect(jsonPath("$.beleg.grossTotal").value(4.15))
                .andExpect(jsonPath("$.inputTokens").value(1100));
        mvc.perform(get("/api/documents/{id}/processing", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.account.chart").value("SKR03"))
                .andExpect(jsonPath("$.account.inputTaxAccounts['7']").value("1571"));
        then(corrector).should(never()).correct(any(), any(), any(), anyList());
    }

    @Test
    void validationErrorIsFixedBySelfCorrection() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-corrected");
        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.54")));
        given(corrector.correct(eq(png), eq(FileKind.PNG), any(), anyList()))
                .willReturn(outcome("strong-model", receipt("0.45")));

        String id = upload(png);
        awaitStatus(id, "ACCEPTED");

        mvc.perform(get("/api/documents/{id}/processing", id))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].source").value("MODEL"))
                .andExpect(jsonPath("$.attempts[0].chosen").value(false))
                .andExpect(jsonPath("$.attempts[0].validation.findings[*].code").value(hasItem("TAX_LINE_TAX_MISMATCH")))
                .andExpect(jsonPath("$.attempts[1].source").value("MODEL_CORRECTION"))
                .andExpect(jsonPath("$.attempts[1].model").value("strong-model"))
                .andExpect(jsonPath("$.attempts[1].chosen").value(true));
        mvc.perform(get("/api/documents/{id}/extraction", id))
                .andExpect(jsonPath("$.model").value("strong-model"))
                .andExpect(jsonPath("$.attempt").value(2));
    }

    @Test
    void remainingErrorsGoToReviewAndNeedAReasonToApprove() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-review-approve");
        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.54")));
        given(corrector.correct(eq(png), eq(FileKind.PNG), any(), anyList()))
                .willReturn(outcome("strong-model", receipt("0.54")));

        String id = upload(png);
        awaitStatus(id, "NEEDS_REVIEW");

        mvc.perform(get("/api/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentId == '%s')].errors[*].code".formatted(id)).value(hasItem("TAX_LINE_TAX_MISMATCH")));

        mvc.perform(post("/api/documents/{id}/review", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"reviewer\":\"anna\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errors[0].code").value("TAX_LINE_TAX_MISMATCH"));

        mvc.perform(post("/api/documents/{id}/review", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"reviewer\":\"anna\",\"comment\":\"Receipt really prints 0,54\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("APPROVE"))
                .andExpect(jsonPath("$.reviewer").value("anna"));
        awaitStatus(id, "APPROVED");

        mvc.perform(post("/api/documents/{id}/review", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"APPROVE\",\"reviewer\":\"bob\",\"comment\":\"again\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reviewerCorrectionIsValidatedAndStoredNextToTheModelOutput() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-review-correct");
        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.54")));
        given(corrector.correct(eq(png), eq(FileKind.PNG), any(), anyList()))
                .willReturn(outcome("strong-model", receipt("0.54")));

        String id = upload(png);
        awaitStatus(id, "NEEDS_REVIEW");

        DocumentContext extraction = JsonPath.parse(mvc.perform(get("/api/documents/{id}/extraction", id))
                .andReturn().getResponse().getContentAsString());
        extraction.set("$.beleg.taxLines[0].tax", 0.45)
                .set("$.beleg.taxLines[0].gross", 2.80)
                .set("$.beleg.taxTotal", 0.54);
        String corrected = JsonPath.parse((Object) extraction.read("$.beleg")).jsonString();

        mvc.perform(post("/api/documents/{id}/review", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CORRECT\",\"reviewer\":\"anna\",\"account\":\"4650\",\"beleg\":" + corrected + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("CORRECT"))
                .andExpect(jsonPath("$.validation.findings.length()").value(0))
                .andExpect(jsonPath("$.beleg.taxLines[0].tax").value(0.45))
                .andExpect(jsonPath("$.account").value("4650"));

        // the model output is unchanged (GoBD)
        mvc.perform(get("/api/documents/{id}/extraction", id))
                .andExpect(jsonPath("$.beleg.taxLines[0].tax").value(0.54));
        mvc.perform(get("/api/documents/{id}/review", id))
                .andExpect(jsonPath("$.action").value("CORRECT"));
    }

    @Test
    void failedExtractionMarksDocumentFailed() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-fail");
        given(primary.extract(eq(png), any())).willThrow(new ExtractionException("model unavailable"));

        String id = upload(png);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/documents/{id}", id))
                        .andExpect(jsonPath("$.status").value("FAILED"))
                        .andExpect(jsonPath("$.lastError").value("model unavailable"))
                        .andExpect(jsonPath("$.attempts").value(1)));
    }

    @Test
    void transientFailureIsRetriedWithBackoff() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-retry");
        given(primary.extract(eq(png), eq(FileKind.PNG)))
                .willThrow(ExtractionException.retryable("HTTP 429 rate limit", null))
                .willReturn(outcome("cheap-model", receipt("0.45")));

        String id = upload(png);
        awaitStatus(id, "ACCEPTED");

        mvc.perform(get("/api/documents/{id}", id))
                .andExpect(jsonPath("$.attempts").value(2))
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void exhaustedRetriesGoToDeadLetterAndCanBeRequeued() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-dead-letter");
        given(primary.extract(eq(png), eq(FileKind.PNG)))
                .willThrow(ExtractionException.retryable("HTTP 503 high demand", null));

        String id = upload(png);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/documents/{id}", id))
                        .andExpect(jsonPath("$.status").value("DEAD_LETTER"))
                        .andExpect(jsonPath("$.attempts").value(3))
                        .andExpect(jsonPath("$.lastError").value("Gave up after 3 attempts: HTTP 503 high demand")));

        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.45")));
        mvc.perform(post("/api/documents/{id}/requeue", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts").value(0));
        awaitStatus(id, "ACCEPTED");
        mvc.perform(post("/api/documents/{id}/requeue", id)).andExpect(status().isConflict());
    }

    @Test
    void transientCorrectionFailureRetriesTheDocumentInsteadOfSendingItToReview() throws Exception {
        byte[] png = DocumentApiTests.png("pipeline-correction-503");
        given(primary.extract(eq(png), eq(FileKind.PNG))).willReturn(outcome("cheap-model", receipt("0.54")));
        given(corrector.correct(eq(png), eq(FileKind.PNG), any(), anyList()))
                .willThrow(ExtractionException.retryable("HTTP 503 high demand", null))
                .willReturn(outcome("strong-model", receipt("0.45")));

        String id = upload(png);
        awaitStatus(id, "ACCEPTED");

        mvc.perform(get("/api/documents/{id}", id)).andExpect(jsonPath("$.attempts").value(2));
        mvc.perform(get("/api/documents/{id}/extraction", id)).andExpect(jsonPath("$.model").value("strong-model"));
    }

    @Test
    void documentsRunInParallelUpToTheConcurrencyLimit() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        List<byte[]> pngs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pngs.add(DocumentApiTests.png("pipeline-parallel-" + i));
        }
        for (byte[] png : pngs) {
            given(primary.extract(eq(png), eq(FileKind.PNG))).willAnswer(invocation -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.sleep(300);
                running.decrementAndGet();
                return outcome("cheap-model", receipt("0.45"));
            });
        }

        List<String> ids = new ArrayList<>();
        for (byte[] png : pngs) {
            ids.add(upload(png));
        }
        for (String id : ids) {
            awaitStatus(id, "ACCEPTED");
        }

        assertThat(maxRunning.get()).isBetween(2, 4);
    }

    private void awaitStatus(String id, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/documents/{id}", id)).andExpect(jsonPath("$.status").value(expected)));
    }

    private String upload(byte[] content) throws Exception {
        String body = mvc.perform(multipart("/api/documents").file(DocumentApiTests.file("bon.png", content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Bakery receipt with 19 % and 7 %; {@code tax19} is the tax of the 19 % line (0.45 is correct, 0.54 a misreading). */
    private static Beleg receipt(String tax19) {
        BigDecimal tax = new BigDecimal(tax19);
        return new Beleg(DocumentKind.RECEIPT, "Bäckerei Schmidt", "Marktstr. 2, 04109 Leipzig", null, null, null, "0815",
                LocalDate.of(2026, 11, 3), null, "EUR",
                List.of(new Position("Brötchen", new BigDecimal("3"), new BigDecimal("0.45"),
                                new BigDecimal("1.35"), new BigDecimal("7")),
                        new Position("Kaffee", BigDecimal.ONE, new BigDecimal("2.80"),
                                new BigDecimal("2.80"), new BigDecimal("19"))),
                List.of(new TaxLine(new BigDecimal("19"), new BigDecimal("2.35"), tax, new BigDecimal("2.35").add(tax)),
                        new TaxLine(new BigDecimal("7"), new BigDecimal("1.26"), new BigDecimal("0.09"),
                                new BigDecimal("1.35"))),
                null, tax.add(new BigDecimal("0.09")), new BigDecimal("4.15"), null, "BAR");
    }

    private static ExtractionOutcome outcome(String model, Beleg beleg) {
        return new ExtractionOutcome(beleg, "{\"sellerName\":\"Bäckerei Schmidt\"}", model, 1100, 400, null,
                Duration.ofMillis(1234), 1, 1);
    }
}
