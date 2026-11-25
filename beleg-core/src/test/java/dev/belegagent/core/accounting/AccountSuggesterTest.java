package dev.belegagent.core.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountSuggesterTest {

    private final AccountSuggester skr03 = new AccountSuggester(ChartOfAccounts.SKR03);
    private final AccountSuggester skr04 = new AccountSuggester(ChartOfAccounts.SKR04);

    @Test
    void restaurantIsBewirtungWithDeductionNote() {
        Beleg b = beleg("Trattoria Roma", List.of("Pizza", "Cola"), "7", "19");

        AccountSuggestion s = skr03.suggest(b);

        assertThat(s.category()).isEqualTo(ExpenseCategory.ENTERTAINMENT);
        assertThat(s.account()).isEqualTo("4650");
        assertThat(s.confidence()).isEqualTo(AccountSuggestion.Confidence.HIGH);
        assertThat(s.matchedKeywords()).containsExactly("trattoria");
        assertThat(s.note()).contains("70 %", "4654");
        assertThat(s.inputTaxAccounts()).containsEntry("7", "1571").containsEntry("19", "1576");
    }

    @Test
    void sameDocumentInSkr04() {
        AccountSuggestion s = skr04.suggest(beleg("Trattoria Roma", List.of("Pizza"), "7"));

        assertThat(s.account()).isEqualTo("6640");
        assertThat(s.note()).contains("6644");
        assertThat(s.inputTaxAccounts()).containsExactly(java.util.Map.entry("7", "1401"));
    }

    @Test
    void fuelReceiptIsVehicleCost() {
        AccountSuggestion s = skr03.suggest(beleg("ARAL Tankstelle Berlin-Mitte", List.of("Super E10", "Kaffee"), "19"));

        assertThat(s.category()).isEqualTo(ExpenseCategory.VEHICLE);
        assertThat(s.account()).isEqualTo("4530");
        assertThat(s.matchedKeywords()).contains("tankstelle", "aral", "super e10");
    }

    @Test
    void lineItemsDecideWhenTheSellerIsGeneric() {
        AccountSuggestion s = skr03.suggest(beleg("Kaufhaus Müller", List.of("Kopierpapier A4", "Toner schwarz"), "19"));

        assertThat(s.category()).isEqualTo(ExpenseCategory.OFFICE_SUPPLIES);
        assertThat(s.account()).isEqualTo("4930");
        assertThat(s.confidence()).isEqualTo(AccountSuggestion.Confidence.MEDIUM);
    }

    @Test
    void umlautsAndAccentsAreNormalized() {
        assertThat(skr03.suggest(beleg("Bürobedarf Meier", List.of(), "19")).category())
                .isEqualTo(ExpenseCategory.OFFICE_SUPPLIES);
        assertThat(skr03.suggest(beleg("Café Central", List.of(), "19")).category())
                .isEqualTo(ExpenseCategory.ENTERTAINMENT);
    }

    @Test
    void shortKeywordsMatchWholeWordsOnly() {
        // "jet" must not match JetBrains; "jetbrains" (software) must
        AccountSuggestion s = skr03.suggest(beleg("JetBrains s.r.o.", List.of("IntelliJ IDEA Ultimate"), "19"));

        assertThat(s.category()).isEqualTo(ExpenseCategory.SOFTWARE);
        assertThat(s.account()).isEqualTo("4964");
    }

    @Test
    void unknownSellerFallsBackWithLowConfidence() {
        AccountSuggestion s = skr04.suggest(beleg("Muster GmbH", List.of("Position 1"), "16"));

        assertThat(s.category()).isEqualTo(ExpenseCategory.OTHER);
        assertThat(s.account()).isEqualTo("6300");
        assertThat(s.confidence()).isEqualTo(AccountSuggestion.Confidence.LOW);
        assertThat(s.matchedKeywords()).isEmpty();
        assertThat(s.inputTaxAccounts()).isEmpty();
    }

    private static Beleg beleg(String seller, List<String> items, String... rates) {
        List<Position> positions = items.stream()
                .map(d -> new Position(d, BigDecimal.ONE, null, BigDecimal.TEN, null))
                .toList();
        List<TaxLine> taxLines = java.util.Arrays.stream(rates)
                .map(r -> new TaxLine(new BigDecimal(r), null, null, null))
                .toList();
        return new Beleg(DocumentKind.RECEIPT, seller, null, null, null, null, null, LocalDate.of(2026, 11, 1), null,
                "EUR", positions, taxLines, null, null, BigDecimal.TEN, null, null);
    }
}
