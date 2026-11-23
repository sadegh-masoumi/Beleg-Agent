package dev.belegagent.core;

import java.math.BigDecimal;

/**
 * One row of the VAT summary: amounts for a single tax rate.
 * Restaurant receipts typically have two rows (7 % food, 19 % drinks).
 */
public record TaxLine(
        BigDecimal ratePercent,
        BigDecimal net,
        BigDecimal tax,
        BigDecimal gross) {
}
