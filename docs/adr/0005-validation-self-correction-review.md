# ADR 0005: Deterministic validation, one self-correction, human review

- Status: accepted
- Date: 2026-11-23

## Context
Phase 3 turns the single model call into an agent: route, extract, check, correct, decide. The model is good at reading but
cannot be trusted to judge its own output, and a German invoice has hard, checkable rules (§14 Abs. 4 UStG, §33 UStDV, tax per rate).
GoBD requires that no change hides the original content.

## Decision
1. **Control flow is fixed Java code** (`BelegAgent` in `beleg-extraction`), not an LLM planning loop. The model is only called to read a document.
2. **Routing:** a PDF with an embedded full e-invoice (ZUGFeRD/Factur-X/XRechnung, not MINIMUM or BASIC-WL) is read from its XML with no model call.
   The XML is authoritative, so validation errors on it go straight to review, never to the model.
3. **Validator** (`BelegValidator` in `beleg-core`) produces findings with a severity:
   - `ERROR`: a mandatory field is missing (§33 UStDV up to 250 € gross, §14 Abs. 4 UStG above) or amounts do not add up
     (tax = net × rate rounded to 2 decimals, 1 cent tolerance per rate; tax-line sums = document totals; line items = total on receipts).
   - `WARNING`: unusual but possibly correct (unknown tax rate, missing service date, line-item sum off on an invoice with
     document-level discounts, per-rate gross inconsistent while net and tax match the rate).
4. **Self-correction, once:** on errors, the validator messages and the previous answer go to `GEMINI_STRONG_MODEL`. The prompt tells it to
   fix misreadings only and never adjust values to make the checks pass. The attempt with fewer errors wins (ties go to the correction).
   A failing correction call keeps the first attempt.
5. **Decision:** no errors → `ACCEPTED`; errors remain → `NEEDS_REVIEW`. Warnings never block.
6. **Review:** `POST /api/documents/{id}/review` with `APPROVE` or `CORRECT`. A correction is validated with the same rules.
   Data with remaining errors can only be approved with a comment, so every exception to the rules is explained.
   Status becomes `APPROVED` via a conditional update (two concurrent reviews cannot both win).
7. **Append-only storage:** every attempt is an `extraction` row (`source`, `attempt`, `chosen`, `validation`), the run is a
   `processing_run` row (decision, account suggestion, notes), the human decision a `review` row. Nothing is updated or deleted.
8. **Account suggestion is deterministic** (`AccountSuggester`, keyword rules over seller name and line items, SKR03/SKR04),
   not an LLM tool call: it is explainable (matched keywords are stored), free, and the reviewer confirms it anyway.

## Calibration (evals, 2026-11-23)
- All 23 ground-truth XMLs of the eval set pass the validator, so the rules do not reject correct invoices.
- The first version rejected three first answers whose net and tax per rate were right but whose per-rate gross was misread.
  Gross per rate is redundant (net + tax) and not a §14 field, so this case became a warning; net + tax is used for the sums.
  After that every rejection on the eval set was a real error in the VAT breakdown.

## Consequences
- Self-correction only helps where the rules notice a problem. Misreadings that keep the sums consistent (a wrong invoice number,
  a wrong date) pass; the evals report them as "accepted, key fields wrong".
- The strong model is called only for rejected documents, so its cost scales with the rejection rate, not the volume.
- No review UI yet: the queue and decisions are REST endpoints. Authentication (reviewer identity) arrives with the API security work.
- The keyword table and the SKR account numbers must be verified against the current DATEV charts before production use.
