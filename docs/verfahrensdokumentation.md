# Verfahrensdokumentation (GoBD)

> Draft. Filled in as the system is built. Required by GoBD for any system that processes Buchungsbelege.

## 1. Allgemeine Beschreibung
Purpose, users, scope of Beleg-Agent. *(Phase 1)*

## 2. Anwenderdokumentation
How documents are uploaded, reviewed, corrected and exported. *(Phase 3–5)*

## 3. Technische Systemdokumentation
Architecture, data flow, storage locations, model providers. See [PLAN.md](PLAN.md). *(ongoing)*

## 4. Betriebsdokumentation
Backup, retention (8 years for Buchungsbelege, §147 AO), Object Lock configuration, access control. *(Phase 4–5)*

## 5. Internes Kontrollsystem (IKS)
- Deterministic validation of every extraction
- Human review for uncertain documents
- Append-only audit log with hash chain; model output and human correction both retained
