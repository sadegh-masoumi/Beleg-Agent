/**
 * Processing (worker role): consumes jobs (PostgreSQL SKIP LOCKED queue in the MVP),
 * routes, extracts, validates, runs the one-shot self-correction loop and suggests SKR accounts.
 */
package dev.belegagent.app.processing;
