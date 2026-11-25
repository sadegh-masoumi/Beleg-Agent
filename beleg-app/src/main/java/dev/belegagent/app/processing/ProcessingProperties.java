package dev.belegagent.app.processing;

import dev.belegagent.core.accounting.ChartOfAccounts;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent settings.
 *
 * @param selfCorrection  send validator errors back to the strong model once (off only to measure the baseline)
 * @param chartOfAccounts SKR03 or SKR04 for account suggestions
 */
@ConfigurationProperties("beleg.processing")
public record ProcessingProperties(
        @DefaultValue("true") boolean selfCorrection,
        @DefaultValue("SKR03") ChartOfAccounts chartOfAccounts) {
}
