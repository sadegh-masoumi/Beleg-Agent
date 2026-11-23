package dev.belegagent.extraction.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.belegagent.core.DocumentKind;
import dev.langchain4j.model.output.structured.Description;
import java.math.BigDecimal;
import java.util.List;

/**
 * Model-facing schema. LangChain4j turns this record into the JSON schema sent to Gemini.
 * Dates are strings here because LangChain4j has no JSON-schema mapping for {@code LocalDate};
 * {@link BelegMapper} converts to the normalized {@link dev.belegagent.core.Beleg}.
 */
@Description("Data extracted from one German receipt (Kassenbon) or invoice (Rechnung)")
public record ExtractedBeleg(
        @JsonProperty(required = true)
        @Description("RECEIPT for till receipts (Kassenbon), INVOICE for invoices (Rechnung), CREDIT_NOTE if the document is titled Gutschrift, otherwise OTHER")
        DocumentKind kind,

        @JsonProperty(required = true)
        @Description("Seller or merchant name exactly as printed")
        String sellerName,

        @Description("Seller postal address on one line (street, postcode, city), or null if not printed")
        String sellerAddress,

        @Description("Seller VAT ID (USt-IdNr., format DE + 9 digits), or null if not printed")
        String sellerVatId,

        @Description("Seller tax number (Steuernummer / St.-Nr.), or null if not printed")
        String sellerTaxNumber,

        @Description("Buyer / customer name, or null if not printed (usually absent on receipts)")
        String buyerName,

        @Description("Invoice or receipt number (Rechnungsnummer, Beleg-Nr., Bon-Nr.), or null if not printed")
        String invoiceNumber,

        @JsonProperty(required = true)
        @Description("Issue date (Rechnungsdatum / Datum) in ISO format yyyy-MM-dd")
        String issueDate,

        @Description("Delivery or service date (Liefer-/Leistungsdatum) in ISO format yyyy-MM-dd, or null if not printed")
        String serviceDate,

        @JsonProperty(required = true)
        @Description("ISO 4217 currency code, EUR unless another currency is printed")
        String currency,

        @JsonProperty(required = true)
        @Description("All line items in printed order. Empty list if none are printed")
        List<ExtractedPosition> positions,

        @JsonProperty(required = true)
        @Description("VAT summary: one entry per tax rate as printed (e.g. A 19% / B 7%). Empty list if no VAT summary is printed")
        List<ExtractedTaxLine> taxLines,

        @Description("Total net amount (Netto), or null if not printed")
        BigDecimal netTotal,

        @Description("Total VAT amount (MwSt./USt. gesamt), or null if not printed")
        BigDecimal taxTotal,

        @JsonProperty(required = true)
        @Description("Gross invoice total including VAT (Summe / Gesamtbetrag / Rechnungsbetrag brutto), BEFORE deducting prepayments, deposits or advance payments")
        BigDecimal grossTotal,

        @Description("Amount still to be paid (Zahlbetrag / zu zahlen / offener Betrag) after prepayments or deposits; null if no separate amount due is printed")
        BigDecimal amountDue,

        @Description("Payment method as printed (e.g. BAR, EC-Karte, Kreditkarte, Überweisung), or null")
        String paymentMethod) {
}
