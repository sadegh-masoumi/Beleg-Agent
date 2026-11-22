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
Phase 0 (setup) done. Next: Phase 1, basic extraction (upload → MinIO → `Beleg`/`Position` records via LangChain4j + Gemini; model chosen by config).
