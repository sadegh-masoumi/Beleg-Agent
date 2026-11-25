package dev.belegagent.extraction.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import dev.belegagent.core.accounting.AccountSuggester;
import dev.belegagent.core.accounting.ChartOfAccounts;
import dev.belegagent.core.accounting.ExpenseCategory;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.core.validation.Finding;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.einvoice.EInvoice;
import dev.belegagent.extraction.einvoice.EInvoiceProfile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BelegAgentTest {

    private static final byte[] PDF = "%PDF-1.7 test".getBytes();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    private final BelegValidator validator =
            new BelegValidator(Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneId.of("Europe/Berlin")));
    private final AccountSuggester accounts = new AccountSuggester(ChartOfAccounts.SKR03);

    @Test
    void validFirstAnswerIsAcceptedWithoutCorrection() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("1.61"));
        FakeExtractor strong = new FakeExtractor("strong", receipt("1.61"));

        AgentResult result = agent(cheap, strong, true, noEInvoice()).process(PNG, FileKind.PNG);

        assertThat(result.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(result.attempts()).extracting(Attempt::source).containsExactly(Source.MODEL);
        assertThat(result.selfCorrected()).isFalse();
        assertThat(strong.corrections).isEmpty();
        assertThat(result.account().category()).isEqualTo(ExpenseCategory.ENTERTAINMENT);
    }

    @Test
    void validationErrorTriggersOneCorrectionWithTheStrongModel() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("6.11")); // misread 1.61 as 6.11
        FakeExtractor strong = new FakeExtractor("strong", receipt("1.61"));

        AgentResult result = agent(cheap, strong, true, noEInvoice()).process(PNG, FileKind.PNG);

        assertThat(result.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(result.attempts()).extracting(Attempt::source).containsExactly(Source.MODEL, Source.MODEL_CORRECTION);
        assertThat(result.selfCorrected()).isTrue();
        assertThat(result.chosenAttempt().outcome().model()).isEqualTo("strong");
        assertThat(strong.corrections).singleElement().satisfies(errors ->
                assertThat(errors).extracting(Finding::code).contains("TAX_LINE_TAX_MISMATCH"));
        assertThat(result.attempts().getFirst().validation().valid()).isFalse();
    }

    @Test
    void remainingErrorsAfterCorrectionGoToReview() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("6.11"));
        FakeExtractor strong = new FakeExtractor("strong", receipt("6.11"));

        AgentResult result = agent(cheap, strong, true, noEInvoice()).process(PNG, FileKind.PNG);

        assertThat(result.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(result.attempts()).hasSize(2);
        assertThat(result.chosen()).isEqualTo(1);
    }

    @Test
    void worseCorrectionKeepsTheFirstAttempt() {
        Beleg worse = withoutSeller(receipt("6.11"));
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("6.11"));
        FakeExtractor strong = new FakeExtractor("strong", worse);

        AgentResult result = agent(cheap, strong, true, noEInvoice()).process(PNG, FileKind.PNG);

        assertThat(result.chosen()).isZero();
        assertThat(result.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(result.notes()).anyMatch(n -> n.contains("more errors"));
    }

    @Test
    void failingCorrectionCallKeepsTheFirstAttempt() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("6.11"));
        FakeExtractor strong = new FakeExtractor("strong", null);

        AgentResult result = agent(cheap, strong, true, noEInvoice()).process(PNG, FileKind.PNG);

        assertThat(result.attempts()).hasSize(1);
        assertThat(result.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(result.notes()).anyMatch(n -> n.startsWith("Self-correction failed"));
    }

    @Test
    void selfCorrectionCanBeDisabledForBaselineEvals() {
        FakeExtractor strong = new FakeExtractor("strong", receipt("1.61"));

        AgentResult result = agent(new FakeExtractor("cheap", receipt("6.11")), strong, false, noEInvoice())
                .process(PNG, FileKind.PNG);

        assertThat(result.attempts()).hasSize(1);
        assertThat(result.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(strong.corrections).isEmpty();
    }

    @Test
    void firstModelFailurePropagates() {
        BelegAgent agent = agent(new FakeExtractor("cheap", null), new FakeExtractor("strong", null), true, noEInvoice());

        assertThatThrownBy(() -> agent.process(PNG, FileKind.PNG)).isInstanceOf(ExtractionException.class);
    }

    @Test
    void fullEInvoiceIsReadWithoutModel() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("1.61"));
        EInvoice invoice = new EInvoice(receipt("1.61"), EInvoiceProfile.EN16931, "DE");

        AgentResult result = agent(cheap, cheap, true, bytes -> Optional.of(invoice)).process(PDF, FileKind.PDF);

        assertThat(result.attempts()).extracting(Attempt::source).containsExactly(Source.E_INVOICE);
        assertThat(result.chosenAttempt().outcome().model()).isEqualTo("e-invoice/EN16931");
        assertThat(result.decision()).isEqualTo(Decision.ACCEPTED);
        assertThat(cheap.calls).isZero();
    }

    @Test
    void invalidEInvoiceGoesToReviewWithoutModel() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("1.61"));
        EInvoice invoice = new EInvoice(receipt("6.11"), EInvoiceProfile.XRECHNUNG, "DE");

        AgentResult result = agent(cheap, cheap, true, bytes -> Optional.of(invoice)).process(PDF, FileKind.PDF);

        assertThat(result.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(result.attempts()).hasSize(1);
        assertThat(cheap.calls).isZero();
    }

    @Test
    void minimumProfileFallsBackToTheModel() {
        FakeExtractor cheap = new FakeExtractor("cheap", receipt("1.61"));
        EInvoice minimum = new EInvoice(receipt("1.61"), EInvoiceProfile.MINIMUM, "DE");

        AgentResult result = agent(cheap, cheap, true, bytes -> Optional.of(minimum)).process(PDF, FileKind.PDF);

        assertThat(result.attempts()).extracting(Attempt::source).containsExactly(Source.MODEL);
        assertThat(result.notes()).anyMatch(n -> n.contains("MINIMUM"));
        assertThat(cheap.calls).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    private BelegAgent agent(BelegExtractor primary, BelegExtractor corrector, boolean selfCorrection,
                             java.util.function.Function<byte[], Optional<EInvoice>> reader) {
        return new BelegAgent(primary, corrector, validator, accounts, selfCorrection, reader);
    }

    private static java.util.function.Function<byte[], Optional<EInvoice>> noEInvoice() {
        return bytes -> Optional.empty();
    }

    /** Restaurant receipt; {@code tax7} is the tax of the 7 % line (1.61 is correct). */
    private static Beleg receipt(String tax7) {
        BigDecimal tax = new BigDecimal(tax7);
        BigDecimal taxTotal = tax.add(new BigDecimal("1.33"));
        return new Beleg(DocumentKind.RECEIPT, "Trattoria Roma", "Hauptstr. 1, 10115 Berlin", null, null, null, "4711",
                LocalDate.of(2026, 11, 3), null, "EUR",
                List.of(new Position("Pizza", BigDecimal.ONE, null, new BigDecimal("24.61"), new BigDecimal("7")),
                        new Position("Cola", BigDecimal.ONE, null, new BigDecimal("8.33"), new BigDecimal("19"))),
                List.of(new TaxLine(new BigDecimal("7"), null, tax, new BigDecimal("24.61")),
                        new TaxLine(new BigDecimal("19"), null, new BigDecimal("1.33"), new BigDecimal("8.33"))),
                null, taxTotal, new BigDecimal("32.94"), null, "BAR");
    }

    private static Beleg withoutSeller(Beleg b) {
        return new Beleg(b.kind(), null, null, null, null, null, b.invoiceNumber(), b.issueDate(), null, b.currency(),
                b.positions(), b.taxLines(), b.netTotal(), b.taxTotal(), b.grossTotal(), null, null);
    }

    /** Returns a fixed answer; {@code null} makes every call fail. */
    private static final class FakeExtractor implements BelegExtractor {

        private final String model;
        private final Beleg answer;
        final List<List<Finding>> corrections = new ArrayList<>();
        int calls;

        FakeExtractor(String model, Beleg answer) {
            this.model = model;
            this.answer = answer;
        }

        @Override
        public ExtractionOutcome extract(byte[] content, FileKind kind) {
            calls++;
            return answer();
        }

        @Override
        public ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors) {
            calls++;
            corrections.add(errors);
            return answer();
        }

        private ExtractionOutcome answer() {
            if (answer == null) {
                throw new ExtractionException("model unavailable");
            }
            return new ExtractionOutcome(answer, "{}", model, 1000, 300, null, Duration.ofMillis(500), 1, 1);
        }
    }
}
