# ADR 0004: Gemini as the only LLM provider (no local model)

- Status: accepted (supersedes the local-model parts of the original plan)
- Date: 2026-11-22

## Context
The original plan used two switchable providers: Gemini (cloud) for public datasets and a local vision model via Ollama for real receipts.
Running a vision model locally in Docker on macOS is CPU-only (containers cannot use the Apple GPU), slow, and needs ≥ 10 GB of Docker memory.
The decision was made to keep the setup lean and use the cloud only.

## Decision
- Gemini via the Gemini API (`langchain4j-google-ai-gemini`) is the only LLM provider.
- Tiered routing stays: `GEMINI_MODEL` (default `gemini-3.5-flash-lite`) first; `GEMINI_STRONG_MODEL` (default `gemini-3.8-flash`) only when deterministic validation fails.
- E-invoices (ZUGFeRD/XRechnung) still never reach a model (Mustang).
- No Ollama, no local model container, no `testcontainers-ollama`.

## Data protection consequences (important)
- **Free tier:** inputs may be reviewed by humans and used to improve Google products. Use it **only with public datasets** (softmatic, ZUGFeRD corpus, kassenzettel-synth, CORD).
- **Real receipts (`data/own/`, real users):** only with a **paid** tier; for EEA users the Gemini API terms allow only the paid tier.
  For EU data residency move to Vertex AI with the `eu` multi-region or `europe-west3` endpoint (`langchain4j-vertex-ai-gemini`) and sign an AVV/DPA.
- Prompt-content logging stays off by default.

## Other consequences
- Evals compare Gemini models and settings (Flash-Lite vs Flash, low vs medium media resolution, with/without self-correction, image vs OCR + text) instead of cloud vs local.
- The resilience design gains weight: rate limiter (429s), circuit breaker, retry with jitter. Without a local fallback, a provider outage means jobs wait in the queue (or go to the dead-letter queue) rather than switching models.
- Evals need a key: they run locally only (no CI/CD, decided 2026-11-23) with `GEMINI_API_KEY` from `.env` and a small sample to bound cost.
- Reversible: a local model can be added later behind the same AI service interface (e.g. Docker Model Runner, which exposes an OpenAI-compatible API and uses the Mac GPU).
