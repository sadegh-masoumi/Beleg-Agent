# ADR 0001: LangChain4j as the LLM integration layer

- Status: accepted
- Date: 2026-11-22

## Context
The worker must send page images (and PDFs) to a vision model and receive a typed result.
The provider is Gemini (see ADR 0004), with two tiers (cheap model first, stronger model on validation failure).
The stack is Java 25 + Spring Boot 4.2.

## Decision
Use LangChain4j 1.24.0 (core + `langchain4j-google-ai-gemini`) with AI services that return Java records.
Spring Boot starters and `langchain4j-guardrails` are used at `1.24.0-beta34` (beta suffix).

Rules:
- Enable `RESPONSE_FORMAT_JSON_SCHEMA` explicitly; otherwise LangChain4j silently falls back to prompt-only JSON.
- Mark mandatory record fields with `@JsonProperty(required = true)`.
- Output guardrail re-asks once when the JSON is broken; business validation stays in deterministic Java (`beleg-core`), never in the LLM.
- Provider is chosen by configuration so evals can compare models on the same code path.

## Alternatives
- **Spring AI:** good Spring integration and MCP support, but LangChain4j's AI services + guardrails map more directly to the extract → validate → re-ask loop. Spring AI remains a candidate for the MCP server in Phase 5.
- **Raw HTTP clients per provider:** full control, but every provider's structured-output format would have to be reimplemented.

## Consequences
- Beta-versioned starters may change between releases; versions are pinned in the parent POM.
- Since 1.24 the AI service can be mocked without a real model, which keeps unit tests fast.
