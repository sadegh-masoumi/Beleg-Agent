package dev.belegagent.extraction.agent;

import dev.belegagent.core.accounting.AccountSuggester;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.core.validation.ValidationResult;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionException;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.einvoice.EInvoice;
import dev.belegagent.extraction.einvoice.ZugferdReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The processing agent. Its control flow is fixed and deterministic; the model is only used to read documents.
 *
 * <ol>
 *   <li><b>Route:</b> a PDF with a full e-invoice (ZUGFeRD/Factur-X/XRechnung, not MINIMUM or BASIC-WL) is read from
 *       its XML without a model. The XML is authoritative, so validation errors go straight to review.</li>
 *   <li><b>Extract:</b> everything else goes to the cheap model ({@code GEMINI_MODEL}).</li>
 *   <li><b>Validate:</b> {@link BelegValidator} (mandatory fields, tax per rate, sums).</li>
 *   <li><b>Self-correct once:</b> on errors, the errors and the previous answer go to the strong model
 *       ({@code GEMINI_STRONG_MODEL}); the attempt with fewer errors wins (ties go to the correction).</li>
 *   <li><b>Decide:</b> no errors left → {@link Decision#ACCEPTED}; otherwise {@link Decision#NEEDS_REVIEW}.</li>
 *   <li><b>Suggest an account</b> (SKR03/SKR04) for the chosen data.</li>
 * </ol>
 * Pure Java (no Spring), shared by the worker and the eval harness.
 */
public final class BelegAgent {

    private final BelegExtractor primary;
    private final BelegExtractor corrector;
    private final BelegValidator validator;
    private final AccountSuggester accounts;
    private final boolean selfCorrection;
    private final Function<byte[], Optional<EInvoice>> eInvoiceReader;

    /**
     * @param primary        cheap model, first attempt
     * @param corrector      model for the self-correction attempt (usually the strong model; may be {@code primary})
     * @param selfCorrection {@code false} to measure the baseline without correction (evals)
     */
    public BelegAgent(BelegExtractor primary, BelegExtractor corrector, BelegValidator validator,
                      AccountSuggester accounts, boolean selfCorrection) {
        this(primary, corrector, validator, accounts, selfCorrection, ZugferdReader::fromPdf);
    }

    BelegAgent(BelegExtractor primary, BelegExtractor corrector, BelegValidator validator, AccountSuggester accounts,
               boolean selfCorrection, Function<byte[], Optional<EInvoice>> eInvoiceReader) {
        this.primary = Objects.requireNonNull(primary);
        this.corrector = Objects.requireNonNull(corrector);
        this.validator = Objects.requireNonNull(validator);
        this.accounts = Objects.requireNonNull(accounts);
        this.selfCorrection = selfCorrection;
        this.eInvoiceReader = Objects.requireNonNull(eInvoiceReader);
    }

    /**
     * The same agent without the e-invoice route. For evals: the ground truth comes from the embedded XML,
     * so the model must read every document.
     */
    public BelegAgent withoutEInvoiceRouting() {
        return new BelegAgent(primary, corrector, validator, accounts, selfCorrection, bytes -> Optional.empty());
    }

    /**
     * @throws ExtractionException if the first model call fails (the caller marks the document failed);
     *                             a failing correction call only adds a note and the first attempt is kept
     */
    public AgentResult process(byte[] content, FileKind kind) {
        List<String> notes = new ArrayList<>();
        if (kind == FileKind.PDF) {
            Optional<AgentResult> eInvoice = eInvoiceRoute(content, notes);
            if (eInvoice.isPresent()) {
                return eInvoice.get();
            }
        }
        return modelRoute(content, kind, notes);
    }

    private Optional<AgentResult> eInvoiceRoute(byte[] content, List<String> notes) {
        long start = System.nanoTime();
        Optional<EInvoice> read = eInvoiceReader.apply(content);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        EInvoice invoice = read.get();
        if (!invoice.profile().isFullInvoice()) {
            notes.add("Embedded e-invoice XML with profile " + invoice.profile()
                    + " is not a full e-invoice (BMF 15.10.2024); processed with the vision model.");
            return Optional.empty();
        }
        ExtractionOutcome outcome = new ExtractionOutcome(invoice.beleg(), null, "e-invoice/" + invoice.profile(),
                null, null, null, Duration.ofNanos(System.nanoTime() - start), 0, 0);
        notes.add("Read from embedded e-invoice XML (" + invoice.profile() + "), no model call.");
        Attempt attempt = new Attempt(Source.E_INVOICE, outcome, validator.validate(invoice.beleg()));
        return Optional.of(result(List.of(attempt), 0, notes));
    }

    private AgentResult modelRoute(byte[] content, FileKind kind, List<String> notes) {
        List<Attempt> attempts = new ArrayList<>();
        ExtractionOutcome first = primary.extract(content, kind);
        Attempt firstAttempt = new Attempt(Source.MODEL, first, validator.validate(first.beleg()));
        attempts.add(firstAttempt);
        if (firstAttempt.validation().valid() || !selfCorrection) {
            return result(attempts, 0, notes);
        }

        ValidationResult firstValidation = firstAttempt.validation();
        try {
            ExtractionOutcome second = corrector.correct(content, kind, first, firstValidation.errors());
            attempts.add(new Attempt(Source.MODEL_CORRECTION, second, validator.validate(second.beleg())));
        } catch (RuntimeException e) {
            notes.add("Self-correction failed, keeping the first attempt: " + e.getMessage());
            return result(attempts, 0, notes);
        }
        int firstErrors = firstValidation.errors().size();
        int secondErrors = attempts.get(1).validation().errors().size();
        int chosen = secondErrors <= firstErrors ? 1 : 0;
        if (chosen == 0) {
            notes.add("Self-correction produced more errors (%d vs %d); keeping the first attempt."
                    .formatted(secondErrors, firstErrors));
        }
        return result(attempts, chosen, notes);
    }

    private AgentResult result(List<Attempt> attempts, int chosen, List<String> notes) {
        Attempt attempt = attempts.get(chosen);
        Decision decision = attempt.validation().valid() ? Decision.ACCEPTED : Decision.NEEDS_REVIEW;
        return new AgentResult(attempts, chosen, accounts.suggest(attempt.outcome().beleg()), decision, notes);
    }
}
