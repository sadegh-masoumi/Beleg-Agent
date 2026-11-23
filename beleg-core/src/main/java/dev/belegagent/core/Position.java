package dev.belegagent.core;

import java.math.BigDecimal;

/**
 * One line item.
 *
 * @param description     text as printed
 * @param quantity        quantity, {@code null} if not printed
 * @param unitPrice       price per unit, {@code null} if not printed
 * @param lineTotal       line amount as printed (receipts usually print gross amounts)
 * @param taxRatePercent  VAT rate of this line in percent (19, 7, 0), {@code null} if not determinable
 */
public record Position(
        String description,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal taxRatePercent) {
}
