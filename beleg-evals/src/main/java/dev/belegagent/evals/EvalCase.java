package dev.belegagent.evals;

import dev.belegagent.core.Beleg;
import dev.belegagent.extraction.einvoice.EInvoiceProfile;

/**
 * One document with its ground truth.
 *
 * @param id     path relative to the dataset root
 * @param sha256 content hash, used as cache key
 */
record EvalCase(String id, String sha256, byte[] pdf, Beleg truth, EInvoiceProfile profile) {
}
