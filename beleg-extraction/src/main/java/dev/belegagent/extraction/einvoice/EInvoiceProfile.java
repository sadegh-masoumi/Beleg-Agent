package dev.belegagent.extraction.einvoice;

import java.util.Locale;

/**
 * ZUGFeRD / Factur-X / XRechnung profile from {@code GuidelineSpecifiedDocumentContextParameter}.
 * MINIMUM and BASIC-WL are not full e-invoices (BMF-Schreiben 15.10.2024): they carry no line items,
 * so such documents must be processed like ordinary PDFs.
 */
public enum EInvoiceProfile {
    MINIMUM,
    BASIC_WL,
    BASIC,
    EN16931,
    EXTENDED,
    XRECHNUNG,
    UNKNOWN;

    public boolean isFullInvoice() {
        return this == BASIC || this == EN16931 || this == EXTENDED || this == XRECHNUNG;
    }

    static EInvoiceProfile fromGuideline(String guidelineId) {
        if (guidelineId == null) {
            return UNKNOWN;
        }
        String id = guidelineId.toLowerCase(Locale.ROOT);
        if (id.contains("xrechnung")) {
            return XRECHNUNG;
        }
        if (id.contains("extended")) {
            return EXTENDED;
        }
        if (id.contains("basicwl")) {
            return BASIC_WL;
        }
        if (id.contains("minimum")) {
            return MINIMUM;
        }
        if (id.contains("basic")) {
            return BASIC;
        }
        if (id.contains("en16931")) {
            return EN16931;
        }
        return UNKNOWN;
    }
}
