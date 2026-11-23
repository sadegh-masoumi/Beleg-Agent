package dev.belegagent.extraction.einvoice;

import dev.belegagent.core.Beleg;

/**
 * An e-invoice read from structured XML, without any model.
 *
 * @param sellerCountry ISO country code of the seller, {@code null} if absent
 */
public record EInvoice(Beleg beleg, EInvoiceProfile profile, String sellerCountry) {
}
