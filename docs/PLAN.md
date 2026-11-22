# Beleg-Agent: build plan, architecture and scale

English working copy of the original planning document (Persian, 22 Nov 2026).
Items marked **(verify)** were not confirmed in the original and must be checked before relying on them.

> **Deviation from the original plan:** no local model. Gemini is the only LLM provider ([ADR 0004](adr/0004-cloud-only-gemini.md)).
> Ollama, Qwen3-VL, german-ocr-3 and the local-model rows below are kept for reference only.
>
> **Phase 1 simplification:** uploads go through the API as multipart (max 10 MB) instead of presigned PUT, and the worker polls
> the `document` table instead of an outbox-fed queue. Presigned upload, outbox events, retry and dead-letter arrive in Phase 4.

## Summary

Beleg-Agent takes receipts and incoming German invoices (photo or PDF), extracts structured data,
checks it with deterministic rules and produces an export for the accountant.

- **Problem:** small businesses and freelancers still receive paper receipts and PDFs without structured data; manual entry is slow and error-prone.
- **Users:** freelancers, small companies, accountants processing client documents.
- **Output:** structured data per document, SKR03/SKR04 account suggestion, export file for the accountant, review queue for uncertain cases.
- **Key idea:** documents with embedded e-invoice XML (ZUGFeRD/XRechnung) are read deterministically **without an LLM**. Only the rest goes to the vision model.
- **Why it matters for the job market:** German AI job ads ask for exactly this: agentic workflows, evals, human-in-the-loop, guardrails, Java/Spring Boot. This project covers all of them on a real German problem.

## Stack (versions verified on Maven Central, 2026-11-22)

| Area | Tool | Version | Why |
|---|---|---|---|
| Language | Java | 25 LTS | Virtual threads without pinning problems (JEP 491) |
| Framework | Spring Boot | 4.2.0 (released 19 Nov 2026) | 4.0 support ends Dec 2026 |
| LLM | LangChain4j | 1.24.0; Spring starters `1.24.0-beta34` | Image/PDF input, output straight into Java records |
| Guardrails | `langchain4j-guardrails` | `1.24.0-beta34` | Re-ask when the JSON is broken |
| Cloud model | Gemini 3.5 Flash-Lite or 3.8 Flash | – | Free tier to start; image, PDF, JSON Schema |
| Local model | Qwen3-VL 8b, german-ocr-3, GLM-OCR | ~6.1 / 7.2 / 1 GB | Free, data never leaves the machine |
| E-invoice | Mustang | 2.26.0 | Read XML inside PDF via `ZUGFeRDInvoiceImporter` |
| XRechnung validation | KoSIT Validator 1.6.x + XRechnung config 3.0.2 | **(verify)**: Maven Central has `de.kosit:validationtool:1.5.0`; 1.6 may be GitHub-release only | Official reference implementation |
| PDF | Apache PDFBox | 3.0.8 | Page → image, read attachments |
| OCR fallback | tess4j | 5.21.0 | With `deu.traineddata` |
| Database | PostgreSQL + Spring Modulith 2.2.0 | PG 18 | JSONB, outbox, row-level security |
| File storage | MinIO / any S3 | – | Presigned URLs, Object Lock for GoBD |
| Queue | PostgreSQL `SKIP LOCKED`, later RabbitMQ 4 | – | Start simple, scale later |
| Resilience | Bucket4j 8.22 + Spring 7 `@ConcurrencyLimit`, `@Retryable` | – | Cluster-wide rate limit, backpressure |
| Observability | Micrometer, OpenTelemetry, Langfuse, Grafana | – | Cost and latency per model call |
| Tests | Testcontainers | 2.0.5 | PostgreSQL via `@ServiceConnection`, MinIO and Ollama modules |

Technical notes:
- In LangChain4j, `RESPONSE_FORMAT_JSON_SCHEMA` must be enabled explicitly, otherwise it silently falls back to plain prompting, which is unreliable. Mark mandatory fields with `@JsonProperty(required = true)`.
- The official MinIO Docker image is no longer on Docker Hub; Testcontainers docs use a fork. This repo uses `pgsty/minio`.
- On the Gemini free tier, Google may use data to improve its products. Fine for public datasets; for your own real receipts use the local model.

## Architecture

A Spring Boot **modular monolith** with two roles, **API** and **Worker**, built from one codebase but scaled separately.

Files go straight to MinIO; the API only writes metadata and the outbox event. The worker then runs the router:
either the Mustang path (no LLM) or the vision-model path; if the validator rejects, the error message is sent back to the model once.

| Component | Responsibility |
|---|---|
| API | Auth, presigned URLs, document registration, live status via SSE, review |
| Worker | Routing, extraction, validation, self-correction, account suggestion |
| PostgreSQL | Metadata, results (JSONB + key columns), outbox, immutable audit |
| MinIO | Original file, Object Lock retention for GoBD |
| Queue | Decouple upload from processing, retry, dead-letter |
| MCP and export | CSV export for the accountant, read-only tools for Claude Desktop |

### Communication

Upload is synchronous, processing asynchronous; results return via SSE or webhook. Files never pass through the API.

| From → To | Mechanism | Sync? | Payload |
|---|---|---|---|
| Client → API | REST, `Idempotency-Key` header | yes | Upload request, receive presigned URL |
| Client → MinIO | Presigned PUT | yes | Original file (photo/PDF) |
| API → PostgreSQL | One transaction: document row + outbox event | yes | Metadata, content SHA-256, tenant |
| Outbox → Queue | Spring Modulith (Event Publication Registry) | no | Job message (document id, hash) |
| Queue → Worker | MVP: PostgreSQL `SKIP LOCKED`; later RabbitMQ quorum queue | no | Same message |
| Worker → Mustang | In-process library | yes | PDF → invoice XML |
| Worker → vision model | HTTPS to Gemini or Ollama/vLLM behind rate limiter and circuit breaker | yes (inside worker) | Image + prompt → JSON |
| Worker → PostgreSQL | Result + audit event | yes | Raw JSONB + key columns |
| API → Client | SSE; polling as fallback; webhook for B2B | no | Status: queued, processing, review, done |
| Review UI → API | REST | yes | Approve or correct (the correction is stored too) |
| MCP server → API | Read-only | yes | Questions like "office supplies spend in October" |

Job message contract:

```json
{
  "jobId": "7f3c…",
  "documentId": "d-1024",
  "tenantId": "t-01",
  "contentSha256": "9b1e…",
  "objectKey": "t-01/2026/11/d-1024.pdf",
  "attempt": 1
}
```

Four communication rules:
1. **Outbox:** document row and event are written in one transaction. Spring Modulith republishes unfinished events after a restart.
2. **Idempotency:** unique key on `(tenantId, contentSha256)`. A duplicate file is not processed again; the previous result is returned.
3. **Retry:** exponential backoff with full jitter (AWS); after several failures → dead-letter queue.
4. **Why PostgreSQL first:** 1 M documents/day is only ~12 messages/s. PostgreSQL handles up to ~100 concurrent workers; beyond that, vacuum and bloat pressure appear (Microsoft).
   RabbitMQ is then the best fit for slow LLM jobs; Kafka only for export/analytics events.
   In RabbitMQ 4 the default delivery limit is 20, and dead-lettering must be set to at-least-once.

## Processing pipeline (4 decisions, one self-correction loop)

No suspicious document reaches the export without human approval.

- Duplicate files and e-invoices never reach the model; the rest go to the **cheapest model first** and are retried only once with the error message.
- ZUGFeRD profiles MINIMUM and BASIC-WL are **not** full e-invoices; process them as normal PDFs.
- **Validator is deterministic:** schema, mandatory fields, sum of positions, tax per rate with 1 cent tolerance, §14 or §33 UStDV.
- **Self-correction loop:** validator error goes back to the model, a stronger model if needed. Only once; then review queue.
- **Confidence:** from agreement of two paths (e.g. sum of positions vs printed total) and absence of empty fields. Threshold tuned with evals.
- **Human correction:** both the model version and the corrected version are kept (GoBD) and are valuable eval data later.

## German domain rules

Implemented with deterministic Java code, not with the LLM. This part builds the project's validators and guardrails.

### Mandatory invoice fields (§14 Abs. 4 UStG)
1. Full name and address of seller and buyer
2. Seller's Steuernummer or USt-IdNr.
3. Issue date
4. Unique, sequential invoice number
5. Quantity and type of goods or service
6. Date of delivery or service
7. Net amount, separately per tax rate
8. Tax rate and tax amount, or exemption note
9. Retention note in §14b cases
10. The word "Gutschrift" when the buyer issues the invoice

**Kleinbetragsrechnung (§33 UStDV):** if the total is ≤ 250 €, only these are required: seller name and address, date, quantity and type of goods, total amount and tax rate. Most supermarket receipts fall into this category.
Since 1 Jan 2025 Kleinunternehmer invoices must carry the §19 note (IHK).

### Tax and sums
- Rates (§12 UStG): 19 % standard, 7 % reduced. Restaurant food is 7 % but drinks 19 %, so restaurant receipts usually carry two rates.
- Implementation rule: per rate, tax = net × rate, rounded to two decimals (BR-CO-17); then sum(net) + sum(tax) = total.
- No legal rule found for rounding per line or per total: allow 1 cent tolerance per rate.

### E-invoice (E-Rechnung)
- Only structured formats count as E-Rechnung; PDF and JPEG do not. XRechnung and ZUGFeRD from 2.0.1 are accepted, **except MINIMUM and BASIC-WL** profiles (BMF 15.10.2024). The router must check the profile.
- In hybrid formats the XML is authoritative and wins on conflict.
- Transition (§27 Abs. 38 UStG): paper and PDF allowed for deliveries 2025–2026 until end of 2026; for 2027 only if the issuer's previous-year turnover ≤ 800 k€; from 2028 everything electronic.
  So until end of 2027 lots of PDF and paper still arrive; small receipts always.

### GoBD and retention
- Retention for Buchungsbelege is **8 years** (previously 10); books and balance sheets 10 years (§147 AO).
- No change may hide the original content; changes and deletions must be logged, corrections reference the original record (GoBD 2019).
  Implementation: append-only audit table with hash chain, plus Object Lock in MinIO.
- OCR text must be kept after review and correction too, so store both model output and human correction. For E-Rechnung keeping the XML is enough (DATEV).
- The system must have a **Verfahrensdokumentation**: in this project, a document file in the repo (`docs/verfahrensdokumentation.md`).

### SKR03 / SKR04 accounts (examples)

| Expense | SKR03 | SKR04 |
|---|---|---|
| Bürobedarf | 4930 | 6815 |
| Porto | 4910 | 6800 |
| Telefon / Internet | 4920 / 4925 | 6805 / 6810 |
| Bewirtung / nicht abzugsfähig | 4650 / 4654 | 6640 / 6644 |
| Fachliteratur / Fortbildung | 4940 / 4945 | 6820 / 6821 |
| Software (Miete) | 4964 | 6837 |
| Geldverkehr | 4970 | 6855 |
| Vorsteuer 7 % / 19 % | 1571 / 1576 | 1401 / 1406 |

Source: SKR03 2020 edition and SKR04 (secondary source). **(verify)** against the current DATEV version before publishing.

### DATEV export (EXTF Buchungsstapel)
CSV with `;`, ANSI encoding, comma as decimal separator. Key columns: Umsatz, Soll/Haben, Konto, Gegenkonto, BU-Schlüssel,
Belegdatum (TTMM), Belegfeld 1 (max 36 chars), Buchungstext (max 60) (DATEV Dok. 1003221).
**(verify)** Full header field order and format version were not confirmed; get them from the DATEV developer portal.

### DSGVO and EU AI Act
- Invoices contain personal data; an AVV (data processing agreement) with the model provider is required.
- Gemini API terms: on the free tier inputs may be reviewed by humans; for apps serving users in the EEA only the paid tier is allowed (terms, archived version).
- Data residency in Europe: multi-region endpoint `eu` or `europe-west3` on Vertex; the global endpoint gives no guarantee (Google Cloud).
- EU AI Act: Art. 50 applies from 2 Aug 2026. For a back-office extraction tool probably only transparency notices in the chat part (e.g. MCP) are needed (Commission guidance). Not legal advice.

## Evals and tests

Evals are the heart of the project: every change in model, prompt or preprocessing must show in numbers whether it got better or worse.

Key finding: per a Fraunhofer benchmark on SROIE receipts, sending the image directly to the model reached 87.46 % versus at most 74 % with OCR-to-text first; IBAN was the hardest field (arXiv 2509.04469). That study had no German data; this project fills that gap.

### Datasets

| Dataset | Content | Ground truth | License |
|---|---|---|---|
| softmatic/vision-data | Real German receipts (supermarket, pharmacy, gas station), 300 dpi scans | File name: store, date, item count, total | CC-BY-4.0 |
| kassenzettel-synth | ~1000 synthetic German receipts with simulated crumpling | Generated data | MIT |
| ZUGFeRD corpus | Real and test ZUGFeRD PDFs, some with deliberate errors | XML embedded in the PDF | Apache-2.0 |
| xrechnung-testsuite | UBL and CII XMLs (no PDF) | The XML itself | Apache-2.0 |
| CORD | 1000 Indonesian receipts, for comparison | Full labels | CC-BY-4.0 |
| Own receipts | Real data, local only | Manual labels | **Never publish** |

**ZUGFeRD trick:** the model only sees the page image (rendered with PDFBox) and its output is compared with the XML inside the same PDF. A German invoice dataset with exact answers, for free.

### Metrics
- IDs and dates: exact match after normalization (whitespace, date format).
- Amounts: fixed 1 cent tolerance, no partial credit. ANLS is misleading for prices (KIEval).
- Seller name: ANLS (similarity below 0.5 counts as zero).
- Line items: group-level F1 for rows.
- Document level: % of documents the validator accepted, % referred to review, latency and cost per document.

### Comparisons for the README
1. Models: Gemini Flash-Lite, Qwen3-VL 8b, german-ocr-3
2. Image resolution: low vs medium (accuracy vs cost)
3. With and without the self-correction loop
4. Direct image vs OCR + text

### Tests
- Unit: validator and tax calculation with hand-made examples (restaurant receipt with two rates, Kleinbetragsrechnung).
- Integration: Testcontainers with MinIO, PostgreSQL and Ollama; downloaded model cached with `commitToImage`.
- Since LangChain4j 1.24 the AI service can be mocked without a real model, with full call recording.
- CI: GitHub Actions runs a small eval subset with a small local model; if accuracy drops below the threshold, the build fails.

## Scale and bottlenecks

At high volume the main bottleneck is the **model API quota** (requests and tokens per minute) and GPU capacity, not Java threads.
The cheapest Gemini model is cheaper than self-hosting; the main reason to self-host is keeping data in Europe.

### Capacity estimate (assumptions, must be measured)
- Load: 1 M documents/day ≈ 11.6 docs/s. With a 5× month-end peak: ~58 docs/s.
- Concurrent calls (Little's law, rate × latency): at 8 s per call ~460 at peak (~560 with retry margin). A few JVM instances with virtual threads handle this easily.
- Quota needed at peak: ~3500 requests and ~3.9 M input tokens per minute. Probably Tier 3 or Provisioned Throughput on Vertex (unconfirmed).
- Tokens per document: image at medium resolution = 560 tokens + ~500 prompt = ~1100 input, ~400 output. Per Google, OCR quality usually saturates at medium (media resolution).

### Bottlenecks and fixes

| Bottleneck | Symptom | Fix |
|---|---|---|
| Model API quota | 429 errors, queue fills | Shared Bucket4j rate limiter (cluster-wide, not per JVM), backoff with jitter, Provisioned Throughput |
| Gemini 10-minute spend cap | Requests get blocked | Peak ~35 k docs in 10 min with the expensive model is near Tier 3's $200 cap; cheap model first |
| Per-call latency | Lower throughput | Virtual threads + `@ConcurrencyLimit` (Spring 7) for backpressure |
| Image token cost | High bill | Downscale image, medium resolution, minimal thinking |
| Duplicate files | Double processing and cost | Dedup with SHA-256 and result cache |
| E-invoices | Needless LLM calls | Route ZUGFeRD/XRechnung to Mustang, no model |
| GPU (self-hosted) | Multi-minute cold start | 1–2 warm replicas, autoscale on queue depth with KEDA |
| Database | Large tables, slow reports | Monthly partitions, read replica for review and export |
| One large customer | Others starve | Token bucket per tenant, weighted fair queuing |

### Cost per 1000 documents (assuming 1100 input + 400 output tokens)

| Model | Standard ($) | Batch ($) |
|---|---|---|
| Gemini 2.5 Flash-Lite | 0.27 | 0.14 |
| Gemini 3.1 Flash-Lite | 0.88 | – |
| Gemini 2.5 Flash | 1.33 | – |
| Gemini 3 Flash Preview | 1.75 | 0.88 |
| Gemini 3.8 Flash (2026 price) | 2.33 | – |
| Qwen3-VL-8B on H100 with autoscaling | ~0.64 | – |

Prices from the Gemini pricing page; Gemini 3.8 Flash price doubles from 1 Jan 2027. Self-hosting figures are based on GPU vendor numbers and unconfirmed.
Batch API is 50 % cheaper but takes up to 24 h: good for nightly archive processing, not live uploads.

Note: for new projects Google recommends 3.8 Flash or 3.5 Flash-Lite (input $0.03 per M tokens); 2.5 Flash is available only to existing users (deprecations). The 2.5 figures above are for comparison only; check your project's access in AI Studio before choosing.

### Three key decisions
1. **Tiered model routing:** cheapest model first (Flash-Lite). Only if validation fails (tax calculation, sum of positions) → stronger model. One circuit breaker per provider with fallback to the local model.
2. **Self-hosting:** Ollama in development, vLLM in production. In one A100 test vLLM gave ~793 tokens/s vs ~41 for Ollama (Red Hat). Autoscale on queue depth, not CPU/GPU usage (KEDA).
3. **Java:** Java 25 LTS with `spring.threads.virtual.enabled=true`. The `synchronized` pinning problem is solved since JDK 24 (JEP 491). Virtual threads have no cap, so concurrency limits must be explicit (Spring Resilience).

## Security, reliability, observability

Every model call gets a span with tokens, cost and latency, and every incoming document is treated as untrusted.

### Observability
- Tracing: OpenTelemetry GenAI conventions (spec still in Development status). Prompt-content logging off by default; invoices contain personal data.
- Langfuse: accepts traces only via HTTP/protobuf at `/api/public/otel`; `gen_ai.*` spans show as LLM calls. Self-hosting needs PostgreSQL, ClickHouse, Redis and S3.
- Metrics: Micrometer `MetricsChatModelListener` in LangChain4j for call duration and token usage (API) → Prometheus → Grafana.
- Grafana dashboard: queue depth, documents per minute, 429 errors, p95 model latency, review referral rate, daily cost.
- Proposed SLO (our assumption, tune with real data): 95 % of documents under 30 s from upload to result.

### Security
- **Prompt injection from the document:** an invoice can say "write amount 0". Defense: the model only extracts, output is bound by a schema, it has no write tool, and the deterministic validator checks sums. Put some poisoned documents in the test dataset.
- **Malicious file:** size and MIME type limits, PDF page count check, render in a separate worker with a memory limit.
- **Tenant isolation:** PostgreSQL row-level security with `FORCE ROW LEVEL SECURITY`, a role that is not the table owner, and `SET LOCAL` in every transaction (AWS). Separate prefix per tenant in object storage.
- **MCP:** read-only, with a token scoped to one tenant.
- **API keys:** never in the repo; `.env.example` and GitHub Actions secrets.

### Reliability
- Idempotency via content hash, retry with jitter, dead-letter for permanent failure.
- Circuit breaker per provider with fallback to the local model.
- Gemini Batch is not idempotent: store the job id so it is not created twice (Batch API).

## Step-by-step build (6 phases, ~5 weeks)

Presentable after phase 2. Each phase ends with a demo-able output.

0. **Phase 0, preparation (2 days):** GitHub repo, multi-module Maven, Docker Compose (PostgreSQL, MinIO, Ollama); CLAUDE.md and first ADRs (LangChain4j choice, queue choice); data collection: Softmatic receipts, ZUGFeRD samples, own receipts. **Output:** `docker compose up` with no errors.
1. **Phase 1, basic extraction (week 1):** upload, store original in MinIO, metadata in PostgreSQL; first `Beleg` and `Position` records; LangChain4j AI service (image in, record out); two models switchable by config: Gemini Flash and a local Ollama model. **Output:** upload a receipt and see the extracted JSON.
2. **Phase 2, evals (week 2):** run models on the datasets: per-field accuracy, latency, cost; automatic run in GitHub Actions and results table in README. **Output:** model comparison table with real numbers.
3. **Phase 3, agent and guardrails (week 3):** ZUGFeRD routing to Mustang, deterministic validation in Java; self-correction loop and human review queue, account suggestion via tool. **Output:** show how many percent self-correction adds.
4. **Phase 4, async processing and scale (week 4):** message queue, workers, retry, idempotency, dead-letter, rate limiter for the model API; load test (e.g. 1000 documents) and measure bottlenecks. **Output:** throughput chart and bottleneck explanation in README.
5. **Phase 5, polish (week 5):** CSV export for the accountant, tracing, MCP server, Grafana dashboard; final README with diagram, demo GIF, eval table and ADRs. **Output:** project link in CV and LinkedIn.

## Still to verify
- Exact Mustang version and its XRechnung UBL support
- Exact Gemini free-tier quota
- DATEV header field order
- SKR account numbers against the current DATEV version
- KoSIT validator 1.6.x availability on Maven Central (only 1.5.0 found)
