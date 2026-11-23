package dev.belegagent.evals;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Properties;

/** Per-model token prices (USD per 1M tokens) from {@code pricing.properties}. */
final class Pricing {

    private final Properties properties = new Properties();

    Pricing() {
        try (InputStream in = Pricing.class.getResourceAsStream("/pricing.properties")) {
            properties.load(in);
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("pricing.properties missing", e);
        }
    }

    String source() {
        return properties.getProperty("pricing.source", "unknown");
    }

    /** Cost in USD for one call; empty if the model has no price entry. */
    Optional<Double> costUsd(String model, double inputTokens, double billedOutputTokens) {
        String in = properties.getProperty(model + ".input");
        String out = properties.getProperty(model + ".output");
        if (in == null || out == null) {
            return Optional.empty();
        }
        return Optional.of((inputTokens * new BigDecimal(in).doubleValue()
                + billedOutputTokens * new BigDecimal(out).doubleValue()) / 1_000_000d);
    }
}
