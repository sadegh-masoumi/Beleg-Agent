package dev.belegagent.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TaxRateTest {

    @Test
    void standardRateRoundsToTwoDecimals() {
        assertThat(TaxRate.STANDARD.taxFor(new BigDecimal("10.05"))).isEqualByComparingTo("1.91");
    }

    @Test
    void reducedRate() {
        assertThat(TaxRate.REDUCED.taxFor(new BigDecimal("100.00"))).isEqualByComparingTo("7.00");
    }

    @Test
    void zeroRate() {
        assertThat(TaxRate.ZERO.taxFor(new BigDecimal("42.00"))).isEqualByComparingTo("0.00");
    }
}
