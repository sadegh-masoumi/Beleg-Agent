package dev.belegagent.core.validation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class BelegValidatorTest {

    private final BelegValidator validator =
            new BelegValidator(Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneId.of("Europe/Berlin")));

    /** Restaurant: food 7 %, drinks 19 %; the receipt prints gross line amounts and a VAT summary. */
    static Beleg restaurantReceipt() {
        return new Beleg(DocumentKind.RECEIPT, "Trattoria Roma", "Hauptstr. 1, 10115 Berlin", null, "27/123/45678",
                null, "4711", LocalDate.of(2026, 11, 3), null, "EUR",
                List.of(position("Pizza Margherita", "1", "10.70", "7"),
                        position("Spaghetti Carbonara", "1", "13.91", "7"),
                        position("Cola 0,4l", "2", "8.33", "19")),
                List.of(new TaxLine(bd("7"), bd("23.00"), bd("1.61"), bd("24.61")),
                        new TaxLine(bd("19"), bd("7.00"), bd("1.33"), bd("8.33"))),
                bd("30.00"), bd("2.94"), bd("32.94"), null, "EC-Karte");
    }

    /** Full invoice above 250 € with net line items (§14 Abs. 4 UStG). */
    static Beleg fullInvoice() {
        return new Beleg(DocumentKind.INVOICE, "Muster GmbH", "Industriestr. 5, 80331 München", "DE123456789", null,
                "Kunde AG", "RE-2026-0042", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 28), "EUR",
                List.of(position("Beratung", "8", "300.00", "19"), position("Fachbuch", "1", "50.00", "7")),
                List.of(new TaxLine(bd("19"), bd("300.00"), bd("57.00"), bd("357.00")),
                        new TaxLine(bd("7"), bd("50.00"), bd("3.50"), bd("53.50"))),
                bd("350.00"), bd("60.50"), bd("410.50"), bd("410.50"), null);
    }

    @Test
    void restaurantReceiptWithTwoRatesIsValid() {
        ValidationResult result = validator.validate(restaurantReceipt());

        assertThat(result.ruleSet()).isEqualTo(RuleSet.SMALL_AMOUNT);
        assertThat(result.findings()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void smallAmountReceiptNeedsNoBuyerInvoiceNumberOrTaxId() {
        Beleg b = with(restaurantReceipt(), null, null);

        ValidationResult result = validator.validate(b);

        assertThat(result.valid()).isTrue();
        assertThat(codes(result)).doesNotContain("BUYER_NAME_MISSING", "INVOICE_NUMBER_MISSING", "SELLER_TAX_ID_MISSING");
    }

    @Test
    void receiptWithOnlyGrossAndTaxPerRateIsValid() {
        Beleg r = restaurantReceipt();
        Beleg b = new Beleg(r.kind(), r.sellerName(), r.sellerAddress(), null, null, null, null, r.issueDate(), null,
                "EUR", r.positions(),
                List.of(new TaxLine(bd("7"), null, bd("1.61"), bd("24.61")),
                        new TaxLine(bd("19"), null, bd("1.33"), bd("8.33"))),
                null, null, bd("32.94"), null, null);

        assertThat(validator.validate(b).findings()).isEmpty();
    }

    @Test
    void fullInvoiceIsValid() {
        ValidationResult result = validator.validate(fullInvoice());

        assertThat(result.ruleSet()).isEqualTo(RuleSet.FULL_INVOICE);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void fullInvoiceRequiresParagraph14Fields() {
        Beleg i = fullInvoice();
        Beleg b = new Beleg(i.kind(), i.sellerName(), null, null, null, null, null, i.issueDate(), null, "EUR",
                i.positions(), List.of(), i.netTotal(), i.taxTotal(), i.grossTotal(), null, null);

        ValidationResult result = validator.validate(b);

        assertThat(result.valid()).isFalse();
        assertThat(codes(result)).contains("SELLER_ADDRESS_MISSING", "BUYER_NAME_MISSING", "SELLER_TAX_ID_MISSING",
                "INVOICE_NUMBER_MISSING", "TAX_BREAKDOWN_MISSING", "SERVICE_DATE_MISSING");
        assertThat(result.warnings()).extracting(Finding::code).containsExactly("SERVICE_DATE_MISSING");
    }

    @Test
    void taxWithinOneCentIsAccepted() {
        // 10.05 × 19 % = 1.9095 → 1.91; a printed 1.90 is within the 1 cent tolerance
        Beleg b = singleRateReceipt("10.05", "1.90", "11.95");

        assertThat(codes(validator.validate(b))).doesNotContain("TAX_LINE_TAX_MISMATCH");
    }

    @Test
    void wrongTaxPerRateIsAnError() {
        Beleg b = singleRateReceipt("10.05", "1.80", "11.85");

        ValidationResult result = validator.validate(b);

        assertThat(result.errors()).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("TAX_LINE_TAX_MISMATCH");
            assertThat(f.message()).contains("1.80", "10.05", "19 %", "1.91");
        });
    }

    @Test
    void inconsistentGrossPerRateIsOnlyAWarningWhenNetAndTaxMatchTheRate() {
        Beleg i = fullInvoice();
        Beleg b = new Beleg(i.kind(), i.sellerName(), i.sellerAddress(), i.sellerVatId(), null, i.buyerName(),
                i.invoiceNumber(), i.issueDate(), i.serviceDate(), "EUR", i.positions(),
                List.of(new TaxLine(bd("19"), bd("300.00"), bd("57.00"), bd("350.00")),
                        new TaxLine(bd("7"), bd("50.00"), bd("3.50"), bd("53.50"))),
                i.netTotal(), i.taxTotal(), i.grossTotal(), null, null);

        ValidationResult result = validator.validate(b);

        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).extracting(Finding::code).containsExactly("TAX_LINE_GROSS_INCONSISTENT");
    }

    @Test
    void grossReadAsNetIsAnError() {
        Beleg i = fullInvoice();
        Beleg b = new Beleg(i.kind(), i.sellerName(), i.sellerAddress(), i.sellerVatId(), null, i.buyerName(),
                i.invoiceNumber(), i.issueDate(), i.serviceDate(), "EUR", i.positions(),
                List.of(new TaxLine(bd("19"), bd("357.00"), bd("57.00"), bd("357.00")),
                        new TaxLine(bd("7"), bd("50.00"), bd("3.50"), bd("53.50"))),
                i.netTotal(), i.taxTotal(), i.grossTotal(), null, null);

        assertThat(codes(validator.validate(b))).contains("TAX_LINE_SUM_MISMATCH", "TAX_LINE_TAX_MISMATCH",
                "TAX_LINES_NET_MISMATCH");
    }

    @Test
    void totalsThatDoNotAddUpAreAnError() {
        Beleg i = fullInvoice();
        Beleg b = new Beleg(i.kind(), i.sellerName(), i.sellerAddress(), i.sellerVatId(), null, i.buyerName(),
                i.invoiceNumber(), i.issueDate(), i.serviceDate(), "EUR", i.positions(), i.taxLines(),
                bd("350.00"), bd("60.50"), bd("401.50"), null, null);

        assertThat(codes(validator.validate(b))).contains("TOTALS_MISMATCH", "TAX_LINES_GROSS_MISMATCH");
    }

    @Test
    void lineItemSumMismatchIsAnErrorOnReceiptsButAWarningOnInvoices() {
        Beleg r = restaurantReceipt();
        Beleg receipt = new Beleg(r.kind(), r.sellerName(), r.sellerAddress(), null, null, null, null, r.issueDate(),
                null, "EUR", List.of(position("Pizza", "1", "10.70", "7")), r.taxLines(), r.netTotal(), r.taxTotal(),
                r.grossTotal(), null, null);
        Beleg i = fullInvoice();
        Beleg invoice = new Beleg(i.kind(), i.sellerName(), i.sellerAddress(), i.sellerVatId(), null, i.buyerName(),
                i.invoiceNumber(), i.issueDate(), i.serviceDate(), "EUR",
                List.of(position("Beratung", "8", "300.00", "19"), position("Fachbuch", "1", "60.00", "7")),
                i.taxLines(), i.netTotal(), i.taxTotal(), i.grossTotal(), null, null);

        assertThat(validator.validate(receipt).errors()).extracting(Finding::code).containsExactly("POSITIONS_SUM_MISMATCH");
        ValidationResult invoiceResult = validator.validate(invoice);
        assertThat(invoiceResult.valid()).isTrue();
        assertThat(invoiceResult.warnings()).extracting(Finding::code).containsExactly("POSITIONS_SUM_MISMATCH");
    }

    @Test
    void issueDateInTheFutureIsAnError() {
        Beleg r = restaurantReceipt();
        Beleg b = new Beleg(r.kind(), r.sellerName(), r.sellerAddress(), null, null, null, null,
                LocalDate.of(2027, 11, 3), null, "EUR", r.positions(), r.taxLines(), r.netTotal(), r.taxTotal(),
                r.grossTotal(), null, null);

        assertThat(codes(validator.validate(b))).containsExactly("ISSUE_DATE_IN_FUTURE");
    }

    @Test
    void malformedGermanVatIdIsAnError() {
        Beleg i = fullInvoice();
        Beleg b = new Beleg(i.kind(), i.sellerName(), i.sellerAddress(), "DE12345678", null, i.buyerName(),
                i.invoiceNumber(), i.issueDate(), i.serviceDate(), "EUR", i.positions(), i.taxLines(),
                i.netTotal(), i.taxTotal(), i.grossTotal(), null, null);

        assertThat(codes(validator.validate(b))).containsExactly("VAT_ID_INVALID");
    }

    @Test
    void missingRateAndUnusualRateAreReported() {
        Beleg r = restaurantReceipt();
        Beleg noRate = new Beleg(r.kind(), r.sellerName(), r.sellerAddress(), null, null, null, null, r.issueDate(),
                null, "EUR", List.of(position("Ware", "1", "32.94", null)), List.of(), null, null, bd("32.94"), null, null);
        Beleg austrian = singleRateReceipt("100.00", "20.00", "120.00", "20");

        assertThat(codes(validator.validate(noRate))).containsExactly("TAX_RATE_MISSING");
        assertThat(validator.validate(austrian).warnings()).extracting(Finding::code)
                .containsExactlyInAnyOrder("TAX_RATE_UNUSUAL");
    }

    @Test
    void creditNoteWithNegativeAmountsIsValid() {
        Beleg b = new Beleg(DocumentKind.CREDIT_NOTE, "Muster GmbH", "Industriestr. 5, 80331 München", "DE123456789",
                null, "Kunde AG", "GS-7", LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30), "EUR",
                List.of(position("Gutschrift Rücksendung", "1", "-300.00", "19")),
                List.of(new TaxLine(bd("19"), bd("-300.00"), bd("-57.00"), bd("-357.00"))),
                bd("-300.00"), bd("-57.00"), bd("-357.00"), null, null);

        ValidationResult result = validator.validate(b);

        assertThat(result.ruleSet()).isEqualTo(RuleSet.FULL_INVOICE);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void grossTotalAbove250SwitchesToFullInvoiceRules() {
        Beleg b = singleRateReceipt("210.10", "39.92", "250.02");

        ValidationResult result = validator.validate(b);

        assertThat(result.ruleSet()).isEqualTo(RuleSet.FULL_INVOICE);
        assertThat(codes(result)).contains("BUYER_NAME_MISSING", "SELLER_TAX_ID_MISSING");
    }

    // ---------------------------------------------------------------- helpers

    private static Beleg singleRateReceipt(String net, String tax, String gross) {
        return singleRateReceipt(net, tax, gross, "19");
    }

    private static Beleg singleRateReceipt(String net, String tax, String gross, String rate) {
        return new Beleg(DocumentKind.RECEIPT, "Bürobedarf Meier", "Marktplatz 3, 01067 Dresden", null, null, null,
                "B-1", LocalDate.of(2026, 11, 2), null, "EUR",
                List.of(position("Kopierpapier", "1", gross, rate)),
                List.of(new TaxLine(bd(rate), bd(net), bd(tax), bd(gross))),
                bd(net), bd(tax), bd(gross), null, "BAR");
    }

    private static Beleg with(Beleg b, String buyerName, String invoiceNumber) {
        return new Beleg(b.kind(), b.sellerName(), b.sellerAddress(), null, null, buyerName, invoiceNumber,
                b.issueDate(), b.serviceDate(), b.currency(), b.positions(), b.taxLines(), b.netTotal(), b.taxTotal(),
                b.grossTotal(), b.amountDue(), b.paymentMethod());
    }

    static Position position(String description, String quantity, String lineTotal, String rate) {
        return new Position(description, bd(quantity), null, bd(lineTotal), rate == null ? null : bd(rate));
    }

    static BigDecimal bd(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static List<String> codes(ValidationResult result) {
        return result.findings().stream().map(Finding::code).toList();
    }
}
