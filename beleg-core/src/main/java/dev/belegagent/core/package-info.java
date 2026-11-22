/**
 * Domain model ({@code Beleg}, {@code Position}) and deterministic German rules:
 * mandatory invoice fields (§14 Abs. 4 UStG, §33 UStDV), tax and sum checks (1 cent tolerance per rate),
 * SKR03/SKR04 account mapping. Pure Java: no Spring, no LLM.
 */
package dev.belegagent.core;
