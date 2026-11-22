/**
 * Extraction pipeline building blocks.
 * <ul>
 *   <li>Router: duplicate check (SHA-256) → e-invoice (ZUGFeRD/XRechnung, not MINIMUM/BASIC-WL) → Mustang, no LLM;
 *       everything else → vision model.</li>
 *   <li>PDF page rendering with PDFBox.</li>
 *   <li>LangChain4j AI services (Gemini) returning Java records (JSON schema response format must be enabled explicitly).
 *       Tiered: cheapest model first, stronger model only when validation fails.</li>
 * </ul>
 */
package dev.belegagent.extraction;
