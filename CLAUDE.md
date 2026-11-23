# Beleg-Agent

AI agent for German receipts and invoices: extract → deterministic validation → self-correction → human review → DATEV export.
Full plan, domain rules, eval design and phases: [docs/PLAN.md](docs/PLAN.md). Decisions: [docs/adr/](docs/adr/).

## Hard rule: everything runs in Docker
The host has **no Java and no Maven**. Never run `mvn`/`java` on the host. Use:
- `make build` / `make test`: Maven inside the `dev` container (`docker compose --profile dev run --rm dev …`)
- `make up` / `make rebuild` / `make logs` / `make down`: full stack
- `make shell`: bash in the dev container (JDK 25, Maven, Docker CLI, psql)
- `make gemini-check`: list the Gemini models the API key can use
Inside the compose network: `postgres:5432`, `minio:9000`, `api:8080`, `worker:8081`.
From the host: API 18080, worker 18081, PostgreSQL 15432, MinIO 19000 (console 19001). Ports 8080/5432 belong to other projects.

## Modules
- `beleg-core`: domain records + deterministic German rules (§14 UStG, §33 UStDV, tax per rate with 1-cent tolerance, SKR03/04). Pure Java, no Spring, no LLM.
- `beleg-extraction`: router, Mustang (ZUGFeRD/XRechnung, no LLM), PDFBox rendering, LangChain4j AI services (Gemini only, ADR 0004).
- `beleg-app`: Spring Boot 4.2 modular monolith (Spring Modulith). Packages `intake`, `processing`, `review`, `export`, `audit`. One image, roles via profile `api` or `worker`.
- `beleg-evals`: eval harness over `data/`.

## Conventions
- Versions are pinned in the root `pom.xml`; verify on Maven Central before bumping.
- Business validation is **always deterministic Java**, never delegated to the LLM. The model only extracts.
- LangChain4j: enable `RESPONSE_FORMAT_JSON_SCHEMA` explicitly; mandatory fields `@JsonProperty(required = true)`.
- Money: `BigDecimal`, never `double`. Tax per rate rounded to 2 decimals (BR-CO-17).
- GoBD: never update or delete original data; append audit events, keep model output and human correction.
- Secrets only in `.env` (git-ignored); `.env.example` documents the keys.
- LLM = Gemini only, no local model (ADR 0004). `GEMINI_MODEL` first, `GEMINI_STRONG_MODEL` only after validation failure.
- Never send `data/own/` (real receipts) to the Gemini free tier; real receipts need the paid tier.
- `ModularityTests` must stay green: modules talk via events or exposed APIs only.

## Current phase
Phase 0 and Phase 1 done: `POST /api/documents` (multipart, magic-byte check, SHA-256 dedup per tenant) → MinIO + `document` row →
worker claims with `FOR UPDATE SKIP LOCKED` → `BelegExtractionService` (PDFBox render → Gemini, JSON schema) → append-only `extraction` row →
`GET /api/documents/{id}/extraction`. No retry yet (failure = FAILED).
Phase 2 done: `beleg-evals` (`make eval`), ground truth from `ZugferdReader` (Mustang + CII XPath), results in `docs/evals/results.md`,
CI gate `.github/workflows/evals.yml` (needs secret `GEMINI_API_KEY`). Answers are cached per config + prompt/schema fingerprint in `eval-results/`.
Next: Phase 3, agent and guardrails (route full-profile e-invoices to `ZugferdReader`, deterministic validator in `beleg-core`,
self-correction with `GEMINI_STRONG_MODEL`, review queue, SKR account suggestion).

## Gotchas
- Spring Boot 4 uses Jackson 3 (`tools.jackson.*`); annotations stay `com.fasterxml.jackson.annotation`. LangChain4j uses Jackson 2 internally.
- LangChain4j's JSON schema has no `LocalDate`: model-facing records (`extraction.llm.Extracted*`) use strings; `BelegMapper` normalizes into `beleg-core`.
- MinIO client 9 needs `com.squareup.okhttp3:okhttp-jvm` declared explicitly (okhttp 5 classes are not in the `okhttp` artifact for Maven).
- Gemini free tier: gemini-3.8-flash allows 5 requests/min and 20/day; flash-lite much more. Use `--delay-ms` in evals.
- Any change to the prompt or `Extracted*` descriptions changes the eval fingerprint: rerun `make eval` and republish.
- Testcontainers 2: `org.testcontainers.postgresql.PostgreSQLContainer`; MinIO via `pgsty/minio` with `asCompatibleSubstituteFor("minio/minio")`.
