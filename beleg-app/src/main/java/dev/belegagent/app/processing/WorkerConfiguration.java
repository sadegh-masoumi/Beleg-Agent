package dev.belegagent.app.processing;

import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.GeminiSettings;
import dev.belegagent.extraction.PageRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Beans of the worker role only. The API role never talks to the model and starts without a Gemini key.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
@EnableScheduling
class WorkerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfiguration.class);

    /** Fails fast at startup if GEMINI_API_KEY is missing. */
    @Bean
    BelegExtractor belegExtractor(LlmProperties p) {
        GeminiSettings settings = new GeminiSettings(p.geminiApiKey(), p.geminiModel(), p.mediaResolution(),
                p.thinkingLevel(), p.timeout(), p.maxRetries());
        log.info("Extraction model: {}", settings);
        return BelegExtractionService.gemini(settings, new PageRenderer(p.renderDpi(), p.maxPages()));
    }
}
