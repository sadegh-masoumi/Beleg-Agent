# Verfahrensdokumentation (GoBD)

> Draft. Filled in as the system is built. Required by GoBD for any system that processes Buchungsbelege.

## 1. Allgemeine Beschreibung
Purpose, users, scope of Beleg-Agent. *(Phase 1)*

## 2. Anwenderdokumentation
How documents are uploaded, reviewed, corrected and exported. *(Phase 3–5)*

**Review (Phase 3).** Documents whose data fails a deterministic check after one automatic self-correction get status
`NEEDS_REVIEW` and appear in `GET /api/review` with the remaining errors. The reviewer either
- approves the data as extracted (`APPROVE`), or
- sends corrected data (`CORRECT`), which is checked with the same rules.

Data that still violates a rule can only be approved with a written reason (e.g. Kleinunternehmer receipt without VAT, §19 UStG).
The reviewer's name, the decision, the reason and the corrected data are stored; the model output stays unchanged next to it.

## 3. Technische Systemdokumentation
Architecture, data flow, storage locations, model providers. See [PLAN.md](PLAN.md). *(ongoing)*

## 4. Betriebsdokumentation
Backup, retention (8 years for Buchungsbelege, §147 AO), Object Lock configuration, access control. *(Phase 4–5)*

## 5. Internes Kontrollsystem (IKS)
- Deterministic validation of every extraction: mandatory fields (§14 Abs. 4 UStG, §33 UStDV), tax per rate
  (net × rate, 1 cent tolerance), sums of tax lines and line items ([ADR 0005](adr/0005-validation-self-correction-review.md)).
- E-invoices (ZUGFeRD/XRechnung full profiles) are read from the XML without a model; the XML is authoritative.
- One automatic self-correction with a stronger model; the model is instructed never to change values only to pass the checks.
- Human review for every document with remaining errors; approval despite errors requires a written reason.
- Append-only storage: every extraction attempt, its validation, the agent's decision and every human review are separate rows;
  nothing is updated or deleted. *(Audit log with hash chain: Phase 5.)*
