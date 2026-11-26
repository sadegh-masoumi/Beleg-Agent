package dev.belegagent.app.processing;

import dev.belegagent.app.intake.DocumentQueue;
import dev.belegagent.app.intake.DocumentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gauge {@code beleg.queue.documents{status}}: documents per status, refreshed every few seconds
 * (one GROUP BY instead of a query per scrape). Queue depth is the signal to scale workers on (KEDA), not CPU.
 */
@Component
@Profile("worker")
class QueueMetrics {

    private final DocumentQueue queue;
    private final Map<DocumentStatus, AtomicLong> counts = new EnumMap<>(DocumentStatus.class);

    QueueMetrics(DocumentQueue queue, MeterRegistry registry) {
        this.queue = queue;
        for (DocumentStatus status : DocumentStatus.values()) {
            AtomicLong value = new AtomicLong();
            counts.put(status, value);
            Gauge.builder("beleg.queue.documents", value, AtomicLong::get)
                    .description("Documents per status")
                    .tag("status", status.name().toLowerCase())
                    .register(registry);
        }
    }

    @Scheduled(fixedDelay = 5000)
    void refresh() {
        queue.countByStatus().forEach((status, n) -> counts.get(status).set(n));
    }
}
