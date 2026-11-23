package dev.belegagent.app.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param defaultTenant tenant used when no {@code X-Tenant-Id} header is sent (no authentication yet)
 */
@ConfigurationProperties("beleg.intake")
public record IntakeProperties(String defaultTenant) {
}
