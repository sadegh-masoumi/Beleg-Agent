package dev.belegagent.core.validation;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic checks of an extracted {@link Beleg}. The model only extracts; whether the result is acceptable is
 * decided here, never by the model.
 *
 * <ul>
 *   <li>Mandatory fields: §33 UStDV for a Kleinbetragsrechnung (gross total at most 250 €), otherwise §14 Abs. 4 UStG.</li>
 *   <li>Tax per rate: tax = net × rate rounded to 2 decimals (BR-CO-17), 1 cent tolerance per rate;
 *       net + tax = gross per rate and for the document.</li>
 *   <li>Sum of the tax lines equals the document totals; sum of the line items equals the net or gross total.</li>
 *   <li>Formats: USt-IdNr., currency code, issue date not in the future.</li>
 * </ul>
 * Messages contain the concrete numbers because they are sent back to the model for one self-correction attempt.
 */
public final class BelegValidator {

    /** §33 UStDV threshold (gross, since 2017). */
    public static final BigDecimal SMALL_AMOUNT_LIMIT = new BigDecimal("250.00");
    /** Tolerance per tax rate: no legal rule for rounding per line or per total was found. */
    public static final BigDecimal CENT = new BigDecimal("0.01");

    /** §12 UStG rates, plus the temporary 16 % / 5 % of 2020-07-01 to 2020-12-31. */
    private static final List<BigDecimal> KNOWN_RATES = List.of(
            new BigDecimal("19"), new BigDecimal("7"), BigDecimal.ZERO, new BigDecimal("16"), new BigDecimal("5"));
    private static final Pattern DE_VAT_ID = Pattern.compile("DE\\d{9}");
    private static final Pattern OTHER_VAT_ID = Pattern.compile("[A-Z]{2}[0-9A-Z+*]{2,12}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final Clock clock;

    public BelegValidator() {
        this(Clock.systemDefaultZone());
    }

    public BelegValidator(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public ValidationResult validate(Beleg b) {
        RuleSet ruleSet = ruleSet(b);
        List<Finding> findings = new ArrayList<>();
        mandatoryFields(b, ruleSet, findings);
        formats(b, findings);
        List<Amounts> lines = taxLines(b, ruleSet, findings);
        totals(b, lines, findings);
        positions(b, lines, findings);
        return new ValidationResult(ruleSet, findings);
    }

    static RuleSet ruleSet(Beleg b) {
        if (b.grossTotal() == null) {
            return b.kind() == DocumentKind.RECEIPT ? RuleSet.SMALL_AMOUNT : RuleSet.FULL_INVOICE;
        }
        boolean euro = b.currency() == null || "EUR".equals(b.currency());
        return euro && b.grossTotal().abs().compareTo(SMALL_AMOUNT_LIMIT) <= 0 ? RuleSet.SMALL_AMOUNT : RuleSet.FULL_INVOICE;
    }

    // ---------------------------------------------------------------- mandatory fields

    private static void mandatoryFields(Beleg b, RuleSet ruleSet, List<Finding> f) {
        String rule = ruleSet == RuleSet.SMALL_AMOUNT ? "§33 UStDV" : "§14 Abs. 4 UStG";
        if (b.sellerName() == null) {
            f.add(Finding.error("SELLER_NAME_MISSING", "sellerName", "Seller name is missing (required by " + rule + ")."));
        }
        if (b.sellerAddress() == null) {
            f.add(Finding.error("SELLER_ADDRESS_MISSING", "sellerAddress", "Seller address is missing (required by " + rule + ")."));
        }
        if (b.issueDate() == null) {
            f.add(Finding.error("ISSUE_DATE_MISSING", "issueDate", "Issue date is missing or not a valid date."));
        }
        if (b.grossTotal() == null) {
            f.add(Finding.error("GROSS_TOTAL_MISSING", "grossTotal", "Gross total is missing."));
        }
        if (b.positions().isEmpty()) {
            f.add(Finding.error("POSITIONS_MISSING", "positions",
                    "No line items: quantity and type of goods or services are required by " + rule + "."));
        }
        boolean anyRate = b.taxLines().stream().anyMatch(t -> t.ratePercent() != null)
                || b.positions().stream().anyMatch(p -> p.taxRatePercent() != null);
        if (!anyRate) {
            f.add(Finding.error("TAX_RATE_MISSING", "taxLines",
                    "No tax rate found: the document must state the tax rate or an exemption note (e.g. §19 UStG)."));
        }
        if (ruleSet == RuleSet.SMALL_AMOUNT) {
            return;
        }
        if (b.buyerName() == null) {
            f.add(Finding.error("BUYER_NAME_MISSING", "buyerName", "Buyer name is missing (required by §14 Abs. 4 UStG above 250 €)."));
        }
        if (b.sellerVatId() == null && b.sellerTaxNumber() == null) {
            f.add(Finding.error("SELLER_TAX_ID_MISSING", "sellerVatId",
                    "Neither USt-IdNr. nor Steuernummer of the seller found (required by §14 Abs. 4 UStG)."));
        }
        if (b.invoiceNumber() == null) {
            f.add(Finding.error("INVOICE_NUMBER_MISSING", "invoiceNumber", "Invoice number is missing (required by §14 Abs. 4 UStG)."));
        }
        if (b.serviceDate() == null) {
            f.add(Finding.warning("SERVICE_DATE_MISSING", "serviceDate",
                    "No delivery or service date; acceptable only if the document states it equals the issue date."));
        }
        if (anyRate && b.taxLines().isEmpty()) {
            f.add(Finding.error("TAX_BREAKDOWN_MISSING", "taxLines",
                    "No VAT summary: net amount and tax per rate are required by §14 Abs. 4 UStG."));
        }
    }

    // ---------------------------------------------------------------- formats

    private void formats(Beleg b, List<Finding> f) {
        String vatId = b.sellerVatId();
        if (vatId != null) {
            boolean ok = vatId.startsWith("DE") ? DE_VAT_ID.matcher(vatId).matches() : OTHER_VAT_ID.matcher(vatId).matches();
            if (!ok) {
                f.add(Finding.error("VAT_ID_INVALID", "sellerVatId",
                        "USt-IdNr. '" + vatId + "' has an invalid format (German: DE followed by 9 digits)."));
            }
        }
        if (b.currency() != null && !CURRENCY.matcher(b.currency()).matches()) {
            f.add(Finding.error("CURRENCY_INVALID", "currency", "Currency '" + b.currency() + "' is not an ISO 4217 code."));
        }
        if (b.issueDate() != null && b.issueDate().isAfter(LocalDate.now(clock))) {
            f.add(Finding.error("ISSUE_DATE_IN_FUTURE", "issueDate",
                    "Issue date " + b.issueDate() + " is in the future; check the year and the day/month order."));
        }
    }

    // ---------------------------------------------------------------- tax lines

    /** Amounts of one tax line, with the missing third value derived when two are printed. */
    private record Amounts(BigDecimal rate, BigDecimal net, BigDecimal tax, BigDecimal gross) {
    }

    private static List<Amounts> taxLines(Beleg b, RuleSet ruleSet, List<Finding> f) {
        List<Amounts> result = new ArrayList<>();
        List<TaxLine> lines = b.taxLines();
        for (int i = 0; i < lines.size(); i++) {
            TaxLine t = lines.get(i);
            String field = "taxLines[" + i + "]";
            BigDecimal rate = t.ratePercent();
            if (rate == null) {
                f.add(Finding.error("TAX_LINE_RATE_MISSING", field + ".ratePercent", "Tax line " + (i + 1) + " has no tax rate."));
                result.add(new Amounts(null, t.net(), t.tax(), t.gross()));
                continue;
            }
            String label = "Tax line " + pct(rate);
            if (KNOWN_RATES.stream().noneMatch(k -> k.compareTo(rate) == 0)) {
                f.add(Finding.warning("TAX_RATE_UNUSUAL", field + ".ratePercent",
                        label + ": " + pct(rate) + " is not a German VAT rate (19 %, 7 %, 0 %)."));
            }
            BigDecimal net = t.net();
            BigDecimal tax = t.tax();
            BigDecimal gross = t.gross();
            if (net != null && tax != null && gross != null && differs(net.add(tax), gross, CENT)) {
                String message = "%s: net %s + tax %s = %s, but gross is %s.".formatted(label, plain(net), plain(tax),
                        plain(net.add(tax)), plain(gross));
                // Net and tax per rate are the legal values (§14 Abs. 4 Nr. 7/8 UStG); gross per rate is redundant.
                // If net and tax agree with the rate, only the gross value is off: keep net + tax and warn.
                if (differs(tax, taxFor(net, rate), CENT)) {
                    f.add(Finding.error("TAX_LINE_SUM_MISMATCH", field, message));
                } else {
                    f.add(Finding.warning("TAX_LINE_GROSS_INCONSISTENT", field + ".gross",
                            message + " Net and tax match the rate, so net + tax is used."));
                }
                gross = net.add(tax);
            }
            if (net == null && tax != null && gross != null) {
                net = gross.subtract(tax);
            } else if (tax == null && net != null && gross != null) {
                tax = gross.subtract(net);
            } else if (gross == null && net != null && tax != null) {
                gross = net.add(tax);
            }
            if (net != null && tax != null) {
                BigDecimal expected = taxFor(net, rate);
                if (differs(tax, expected, CENT)) {
                    f.add(Finding.error("TAX_LINE_TAX_MISMATCH", field + ".tax",
                            "%s: tax %s does not match net %s × %s = %s (tolerance 0.01)."
                                    .formatted(label, plain(tax), plain(net), pct(rate), plain(expected))));
                }
            } else if (ruleSet == RuleSet.FULL_INVOICE) {
                f.add(Finding.error("TAX_LINE_NET_MISSING", field + ".net",
                        label + ": net amount and tax amount per rate are required by §14 Abs. 4 UStG."));
            }
            result.add(new Amounts(rate, net, tax, gross));
        }
        return result;
    }

    // ---------------------------------------------------------------- totals

    private static void totals(Beleg b, List<Amounts> lines, List<Finding> f) {
        BigDecimal net = b.netTotal();
        BigDecimal tax = b.taxTotal();
        BigDecimal gross = b.grossTotal();
        if (net != null && tax != null && gross != null && differs(net.add(tax), gross, CENT)) {
            f.add(Finding.error("TOTALS_MISMATCH", "grossTotal",
                    "Net total %s + tax total %s = %s, but gross total is %s."
                            .formatted(plain(net), plain(tax), plain(net.add(tax)), plain(gross))));
        }
        if (!lines.isEmpty()) {
            BigDecimal tolerance = CENT.multiply(BigDecimal.valueOf(lines.size()));
            compareSum(f, "TAX_LINES_NET_MISMATCH", "netTotal", "net amounts", sum(lines, Amounts::net), net, tolerance);
            compareSum(f, "TAX_LINES_TAX_MISMATCH", "taxTotal", "tax amounts", sum(lines, Amounts::tax), tax, tolerance);
            compareSum(f, "TAX_LINES_GROSS_MISMATCH", "grossTotal", "gross amounts", sum(lines, Amounts::gross), gross, tolerance);
        }
        BigDecimal due = b.amountDue();
        if (due != null && gross != null && due.abs().compareTo(gross.abs().add(CENT)) > 0) {
            f.add(Finding.warning("AMOUNT_DUE_EXCEEDS_GROSS", "amountDue",
                    "Amount due %s is higher than the gross total %s.".formatted(plain(due), plain(gross))));
        }
    }

    private static void compareSum(List<Finding> f, String code, String field, String what,
                                   BigDecimal sum, BigDecimal total, BigDecimal tolerance) {
        if (sum != null && total != null && differs(sum, total, tolerance)) {
            f.add(Finding.error(code, field, "Sum of the %s in the VAT summary is %s, but %s is %s."
                    .formatted(what, plain(sum), field, plain(total))));
        }
    }

    // ---------------------------------------------------------------- line items

    private static void positions(Beleg b, List<Amounts> lines, List<Finding> f) {
        List<Position> positions = b.positions();
        if (!lines.isEmpty()) {
            Set<String> unknown = new LinkedHashSet<>();
            for (Position p : positions) {
                BigDecimal rate = p.taxRatePercent();
                if (rate != null && lines.stream().noneMatch(l -> l.rate() != null && l.rate().compareTo(rate) == 0)) {
                    unknown.add(pct(rate));
                }
            }
            for (String rate : unknown) {
                f.add(Finding.warning("POSITION_RATE_NOT_IN_SUMMARY", "positions",
                        "Line items use tax rate " + rate + ", which is missing from the VAT summary."));
            }
        }
        if (positions.isEmpty() || positions.stream().anyMatch(p -> p.lineTotal() == null)) {
            return;
        }
        BigDecimal sum = positions.stream().map(Position::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> candidates = new ArrayList<>();
        candidates.add(b.netTotal());
        candidates.add(b.grossTotal());
        candidates.add(sum(lines, Amounts::net));
        candidates.add(sum(lines, Amounts::gross));
        candidates.removeIf(Objects::isNull);
        if (candidates.isEmpty()) {
            return;
        }
        BigDecimal tolerance = CENT.multiply(BigDecimal.valueOf(Math.max(1, lines.size())));
        if (candidates.stream().allMatch(c -> differs(sum, c, tolerance))) {
            String message = "Sum of the line items is %s and matches neither the net total %s nor the gross total %s."
                    .formatted(plain(sum), plain(b.netTotal()), plain(b.grossTotal()));
            // Receipts list every amount as a line; invoices may have document-level discounts or charges.
            f.add(b.kind() == DocumentKind.RECEIPT
                    ? Finding.error("POSITIONS_SUM_MISMATCH", "positions", message)
                    : Finding.warning("POSITIONS_SUM_MISMATCH", "positions",
                            message + " Possibly a document-level discount or charge."));
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Tax for one rate group: net × rate, rounded half-up to two decimals (BR-CO-17). */
    static BigDecimal taxFor(BigDecimal net, BigDecimal ratePercent) {
        return net.multiply(ratePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static boolean differs(BigDecimal a, BigDecimal b, BigDecimal tolerance) {
        return a.subtract(b).abs().compareTo(tolerance) > 0;
    }

    /** Sum of one amount over all lines; {@code null} if any line lacks it. */
    private static BigDecimal sum(List<Amounts> lines, java.util.function.Function<Amounts, BigDecimal> getter) {
        BigDecimal total = BigDecimal.ZERO;
        for (Amounts l : lines) {
            BigDecimal v = getter.apply(l);
            if (v == null) {
                return null;
            }
            total = total.add(v);
        }
        return total;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "not printed" : value.setScale(Math.max(2, value.scale()), RoundingMode.UNNECESSARY).toPlainString();
    }

    private static String pct(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString() + " %";
    }
}
