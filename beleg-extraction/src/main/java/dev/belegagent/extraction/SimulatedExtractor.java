package dev.belegagent.extraction;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import dev.belegagent.core.validation.Finding;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stand-in for the vision model in load tests: no network, no cost, configurable latency and failure rate.
 * Returns a valid restaurant receipt, so the rest of the pipeline (validation, account suggestion, storage) runs for real.
 * Never used unless {@code beleg.llm.provider=simulated} is set explicitly.
 *
 * @param meanLatency mean simulated model latency; actual latency is uniform in [0.5, 1.5] × mean
 * @param failureRate share of calls that fail like an HTTP 429 (retryable), 0..1
 */
public record SimulatedExtractor(String model, Duration meanLatency, double failureRate) implements BelegExtractor {

    public SimulatedExtractor {
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("failureRate must be between 0 and 1");
        }
    }

    @Override
    public ExtractionOutcome extract(byte[] content, FileKind kind) {
        return call();
    }

    @Override
    public ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors) {
        return call();
    }

    private ExtractionOutcome call() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long millis = (long) (meanLatency.toMillis() * (0.5 + random.nextDouble()));
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ExtractionException.retryable("Simulated call interrupted", e);
        }
        if (random.nextDouble() < failureRate) {
            throw ExtractionException.retryable("Model call failed (" + model + "): simulated HTTP 429", null);
        }
        return new ExtractionOutcome(receipt(), null, model, 1100, 400, null, Duration.ofMillis(millis), 1, 1);
    }

    private static Beleg receipt() {
        return new Beleg(DocumentKind.RECEIPT, "Trattoria Simulata", "Hauptstr. 1, 10115 Berlin", null, null, null, "LT-1",
                LocalDate.of(2026, 11, 3), null, "EUR",
                List.of(new Position("Pizza", BigDecimal.ONE, null, new BigDecimal("24.61"), new BigDecimal("7")),
                        new Position("Cola", BigDecimal.ONE, null, new BigDecimal("8.33"), new BigDecimal("19"))),
                List.of(new TaxLine(new BigDecimal("7"), new BigDecimal("23.00"), new BigDecimal("1.61"), new BigDecimal("24.61")),
                        new TaxLine(new BigDecimal("19"), new BigDecimal("7.00"), new BigDecimal("1.33"), new BigDecimal("8.33"))),
                new BigDecimal("30.00"), new BigDecimal("2.94"), new BigDecimal("32.94"), null, "BAR");
    }
}
