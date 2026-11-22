package dev.belegagent.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * German VAT rates (§12 UStG).
 * Restaurant receipts often carry two rates: food 7 %, drinks 19 %.
 */
public enum TaxRate {
    STANDARD(new BigDecimal("19")),
    REDUCED(new BigDecimal("7")),
    ZERO(BigDecimal.ZERO);

    private final BigDecimal percent;

    TaxRate(BigDecimal percent) {
        this.percent = percent;
    }

    public BigDecimal percent() {
        return percent;
    }

    /**
     * Tax for one rate group: net × rate, rounded to two decimals (BR-CO-17).
     */
    public BigDecimal taxFor(BigDecimal net) {
        return net.multiply(percent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
