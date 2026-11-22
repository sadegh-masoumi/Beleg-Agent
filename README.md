# Beleg-Agent

Turns German receipts and incoming invoices (photo or PDF) into structured, validated booking data with SKR03/SKR04 account suggestions and a DATEV export, with a human review queue for anything uncertain.

- E-invoices (ZUGFeRD/XRechnung) are read deterministically, **without an LLM**.
- Everything else goes to Gemini (cheap Flash-Lite first, a stronger model only if validation fails), and the result is checked by deterministic German tax rules.
- Every change to model, prompt or preprocessing is measured with evals.

**Stack:** Java 25 · Spring Boot 4.2 · Spring Modulith · LangChain4j 1.24 · Gemini · Mustang · PDFBox · PostgreSQL 18 · MinIO · Testcontainers

## Quick start (only Docker required)

```bash
cp .env.example .env     # or: make env, then set GEMINI_API_KEY
make up                  # postgres, minio, api, worker
make gemini-check        # verify the key and list available models
make test                # build + tests inside the dev container
```

| Service | URL |
|---|---|
| API health | http://localhost:18080/actuator/health |
| Worker health | http://localhost:18081/actuator/health |
| MinIO console | http://localhost:19001 |
| PostgreSQL | localhost:15432 |

Ports are set in `.env`.

VS Code: install *Dev Containers* and run **Reopen in Container**. You get JDK 25 and Maven inside the container, connected to the same services.

**Data protection:** on the Gemini free tier, inputs may be reviewed and used by Google. Use it only with public datasets; real receipts need the paid tier ([ADR 0004](docs/adr/0004-cloud-only-gemini.md)).

## Architecture

See [docs/PLAN.md](docs/PLAN.md) and [docs/adr/](docs/adr/).

```
client ──REST──▶ api ──tx: document + outbox──▶ PostgreSQL ◀──SKIP LOCKED── worker ──▶ Mustang (e-invoice, no LLM)
   └──presigned PUT──▶ MinIO (Object Lock)                                      └──▶ Gemini → validator → (stronger model once) → review
```

## Roadmap

- [x] Phase 0: repo, multi-module Maven, Docker Compose, ADRs
- [ ] Phase 1: basic extraction
- [ ] Phase 2: evals (model comparison table)
- [ ] Phase 3: agent and guardrails (Mustang routing, validator, self-correction, review)
- [ ] Phase 4: async processing and scale
- [ ] Phase 5: DATEV export, tracing, MCP server, Grafana
