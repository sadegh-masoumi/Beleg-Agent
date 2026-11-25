package dev.belegagent.app.processing;

import dev.belegagent.core.accounting.AccountSuggester;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.GeminiSettings;
import dev.belegagent.extraction.PageRenderer;
import dev.belegagent.extraction.SimulatedExtractor;
import dev.belegagent.extraction.agent.BelegAgent;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Beans of the worker role only. The API role never talks to the model and starts without a Gemini key.
 * Two model tiers: {@code GEMINI_MODEL} for every document, {@code GEMINI_STRONG_MODEL} only for self-correction.
 * Both go through the cluster-wide rate limiter.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class WorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfiguration.class);

    @Bean
    PageRenderer pageRenderer(LlmProperties p) {
        return new PageRenderer(p.renderDpi(), p.maxPages());
    }

    @Bean
    ProxyManager<String> rateLimitBuckets(DataSource dataSource) {
        return Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                .primaryKeyMapper(PrimaryKeyMapper.STRING)
                .table("rate_limit_bucket")
                .idColumn("id")
                .stateColumn("state")
                .expiresAtColumn("expires_at")
                .build();
    }

    @Bean
    ModelRateLimiter modelRateLimiter(ProxyManager<String> rateLimitBuckets, LlmProperties p, MeterRegistry registry) {
        log.info("Model rate limits (cluster-wide, requests/min, 0 = unlimited): {} = {}, {} = {}",
                p.geminiModel(), p.requestsPerMinute(), p.strongModelOrPrimary(), p.strongRequestsPerMinute());
        return new ModelRateLimiter(rateLimitBuckets, p.maxRateLimitWait(), registry);
    }

    /** Fails fast at startup if GEMINI_API_KEY is missing (unless the model is simulated). */
    @Bean
    BelegExtractor primaryExtractor(LlmProperties p, PageRenderer renderer, ModelRateLimiter limiter,
                                    MeterRegistry registry) {
        return new GuardedExtractor(model(p, p.geminiModel(), renderer, "Extraction"), p.geminiModel(),
                p.requestsPerMinute(), limiter, registry);
    }

    /** Falls back to the primary model when no strong model is configured. */
    @Bean
    BelegExtractor correctionExtractor(LlmProperties p, PageRenderer renderer, ModelRateLimiter limiter,
                                       MeterRegistry registry) {
        String model = p.strongModelOrPrimary();
        return new GuardedExtractor(model(p, model, renderer, "Self-correction"), model,
                p.strongRequestsPerMinute(), limiter, registry);
    }

    @Bean
    BelegAgent belegAgent(@Qualifier("primaryExtractor") BelegExtractor primary,
                          @Qualifier("correctionExtractor") BelegExtractor corrector,
                          ProcessingProperties p) {
        log.info("Agent: self-correction {}, chart of accounts {}", p.selfCorrection() ? "on" : "off", p.chartOfAccounts());
        return new BelegAgent(primary, corrector, new BelegValidator(), new AccountSuggester(p.chartOfAccounts()),
                p.selfCorrection());
    }

    private static BelegExtractor model(LlmProperties p, String model, PageRenderer renderer, String role) {
        if (p.simulated()) {
            log.warn("{} model is SIMULATED ({} mean latency, {} failure rate): load-test mode, no real extraction",
                    role, p.simulatedLatency(), p.simulatedFailureRate());
            return new SimulatedExtractor("simulated:" + model, p.simulatedLatency(), p.simulatedFailureRate());
        }
        GeminiSettings settings = new GeminiSettings(p.geminiApiKey(), model, p.mediaResolution(), p.thinkingLevel(),
                p.timeout(), p.maxRetries());
        log.info("{} model: {}", role, settings);
        return BelegExtractionService.gemini(settings, renderer);
    }
}
