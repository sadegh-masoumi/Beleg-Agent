package dev.belegagent.app.review;

import dev.belegagent.core.Beleg;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param beleg    corrected data, required for {@link ReviewAction#CORRECT} and not allowed for APPROVE
 * @param account  booking account chosen by the reviewer; {@code null} keeps the suggested one
 * @param reviewer who decided (no authentication yet, so the client states it)
 * @param comment  required when approving data that still has validation errors
 */
public record ReviewRequest(
        @NotNull ReviewAction action,
        Beleg beleg,
        @Size(max = 10) String account,
        @NotBlank @Size(max = 200) String reviewer,
        @Size(max = 2000) String comment) {
}
