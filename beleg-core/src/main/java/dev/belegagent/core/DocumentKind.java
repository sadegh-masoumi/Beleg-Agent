package dev.belegagent.core;

/**
 * What kind of document a Beleg is. Drives which mandatory-field rules apply:
 * a receipt of at most 250 € is a Kleinbetragsrechnung (§33 UStDV), an invoice needs the full §14 UStG fields,
 * and a credit note issued by the buyer must carry the word "Gutschrift".
 */
public enum DocumentKind {
    RECEIPT,
    INVOICE,
    CREDIT_NOTE,
    OTHER
}
