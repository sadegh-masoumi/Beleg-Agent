package dev.belegagent.extraction.llm;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Converts the model's answer into the normalized domain record. Lenient on format
 * (also accepts German dates), but never invents values: unparseable input becomes {@code null}.
 */
public final class BelegMapper {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d.M.uu"));

    private BelegMapper() {
    }

    public static Beleg toBeleg(ExtractedBeleg e) {
        return new Beleg(
                e.kind() == null ? DocumentKind.OTHER : e.kind(),
                blankToNull(e.sellerName()),
                blankToNull(e.sellerAddress()),
                normalizeVatId(e.sellerVatId()),
                blankToNull(e.sellerTaxNumber()),
                blankToNull(e.buyerName()),
                blankToNull(e.invoiceNumber()),
                parseDate(e.issueDate()),
                parseDate(e.serviceDate()),
                normalizeCurrency(e.currency()),
                e.positions() == null ? List.of() : e.positions().stream()
                        .map(p -> new Position(blankToNull(p.description()), p.quantity(), p.unitPrice(),
                                p.lineTotal(), p.taxRatePercent()))
                        .toList(),
                e.taxLines() == null ? List.of() : e.taxLines().stream()
                        .map(t -> new TaxLine(t.ratePercent(), t.net(), t.tax(), t.gross()))
                        .toList(),
                e.netTotal(),
                e.taxTotal(),
                e.grossTotal(),
                e.amountDue(),
                blankToNull(e.paymentMethod()));
    }

    static LocalDate parseDate(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, format);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    static String normalizeVatId(String value) {
        String v = blankToNull(value);
        return v == null ? null : v.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static String normalizeCurrency(String value) {
        String v = blankToNull(value);
        if (v == null || v.equals("€")) {
            return "EUR";
        }
        return v.toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("null") ? null : value.strip();
    }
}
