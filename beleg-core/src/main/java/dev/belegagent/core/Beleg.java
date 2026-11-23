package dev.belegagent.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Structured data of one receipt or invoice, normalized (real dates, decimal amounts).
 * {@code grossTotal} is the invoice total including VAT; {@code amountDue} is what remains to be paid after
 * prepayments or deposits (equal to the gross total on most documents, {@code null} if not printed separately).
 * Nullable fields are {@code null} when the document does not show them; whether that is allowed
 * is decided by the deterministic validator, not here.
 */
public record Beleg(
        DocumentKind kind,
        String sellerName,
        String sellerAddress,
        String sellerVatId,
        String sellerTaxNumber,
        String buyerName,
        String invoiceNumber,
        LocalDate issueDate,
        LocalDate serviceDate,
        String currency,
        List<Position> positions,
        List<TaxLine> taxLines,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal grossTotal,
        BigDecimal amountDue,
        String paymentMethod) {

    public Beleg {
        positions = positions == null ? List.of() : List.copyOf(positions);
        taxLines = taxLines == null ? List.of() : List.copyOf(taxLines);
    }
}
