package dev.belegagent.app.processing;

import dev.belegagent.core.accounting.AccountSuggester;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.GeminiSettings;
import dev.belegagent.extraction.PageRenderer;
import dev.belegagent.extraction.agent.BelegAgent;
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

    /** Fails fast at startup if GEMINI_API_KEY is missing. */
    @Bean
    BelegExtractor primaryExtractor(LlmProperties p, PageRenderer renderer) {
        GeminiSettings settings = settings(p, p.geminiModel());
        log.info("Extraction model: {}", settings);
        return BelegExtractionService.gemini(settings, renderer);
    }

    /** Falls back to the primary model when no strong model is configured. */
    @Bean
    BelegExtractor correctionExtractor(LlmProperties p, PageRenderer renderer) {
        String model = p.geminiStrongModel() == null || p.geminiStrongModel().isBlank()
                ? p.geminiModel() : p.geminiStrongModel();
        GeminiSettings settings = settings(p, model);
        log.info("Self-correction model: {}", settings);
        return BelegExtractionService.gemini(settings, renderer);
    }

    @Bean
    BelegAgent belegAgent(@Qualifier("primaryExtractor") BelegExtractor primary,
                          @Qualifier("correctionExtractor") BelegExtractor corrector,
                          ProcessingProperties p) {
        log.info("Agent: self-correction {}, chart of accounts {}", p.selfCorrection() ? "on" : "off", p.chartOfAccounts());
        return new BelegAgent(primary, corrector, new BelegValidator(), new AccountSuggester(p.chartOfAccounts()),
                p.selfCorrection());
    }

    private static GeminiSettings settings(LlmProperties p, String model) {
        return new GeminiSettings(p.geminiApiKey(), model, p.mediaResolution(), p.thinkingLevel(), p.timeout(),
                p.maxRetries());
    }
}
