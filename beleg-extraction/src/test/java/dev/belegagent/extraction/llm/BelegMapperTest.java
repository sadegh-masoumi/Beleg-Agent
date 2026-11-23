package dev.belegagent.extraction.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class BelegMapperTest {

    @Test
    void mapsRestaurantReceiptWithTwoRates() {
        ExtractedBeleg extracted = new ExtractedBeleg(
                DocumentKind.RECEIPT, " Gasthaus Zur Linde ", "Hauptstr. 1, 01067 Dresden", "de 123 456 789", null,
                null, "4711", "2026-11-03", null, "€",
                List.of(new ExtractedPosition("Schnitzel", BigDecimal.ONE, new BigDecimal("14.90"),
                                new BigDecimal("14.90"), new BigDecimal("7")),
                        new ExtractedPosition("Apfelschorle", BigDecimal.ONE, new BigDecimal("3.50"),
                                new BigDecimal("3.50"), new BigDecimal("19"))),
                List.of(new ExtractedTaxLine(new BigDecimal("7"), new BigDecimal("13.93"), new BigDecimal("0.97"),
                                new BigDecimal("14.90")),
                        new ExtractedTaxLine(new BigDecimal("19"), new BigDecimal("2.94"), new BigDecimal("0.56"),
                                new BigDecimal("3.50"))),
                null, null, new BigDecimal("18.40"), null, "EC-Karte");

        Beleg beleg = BelegMapper.toBeleg(extracted);

        assertThat(beleg.kind()).isEqualTo(DocumentKind.RECEIPT);
        assertThat(beleg.sellerName()).isEqualTo("Gasthaus Zur Linde");
        assertThat(beleg.sellerVatId()).isEqualTo("DE123456789");
        assertThat(beleg.currency()).isEqualTo("EUR");
        assertThat(beleg.issueDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(beleg.positions()).hasSize(2);
        assertThat(beleg.taxLines()).hasSize(2);
        assertThat(beleg.grossTotal()).isEqualByComparingTo("18.40");
    }

    @Test
    void acceptsGermanDateFormats() {
        assertThat(BelegMapper.parseDate("03.11.2026")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(BelegMapper.parseDate("3.11.26")).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void unparseableValuesBecomeNullInsteadOfGuesses() {
        assertThat(BelegMapper.parseDate("gestern")).isNull();
        assertThat(BelegMapper.parseDate("  ")).isNull();
        assertThat(BelegMapper.parseDate(null)).isNull();
    }

    @Test
    void missingListsBecomeEmpty() {
        Beleg beleg = BelegMapper.toBeleg(new ExtractedBeleg(null, "X", null, null, null, null, null, "2026-01-01",
                null, null, null, null, null, null, BigDecimal.TEN, null, null));

        assertThat(beleg.kind()).isEqualTo(DocumentKind.OTHER);
        assertThat(beleg.positions()).isEmpty();
        assertThat(beleg.taxLines()).isEmpty();
        assertThat(beleg.currency()).isEqualTo("EUR");
    }
}
