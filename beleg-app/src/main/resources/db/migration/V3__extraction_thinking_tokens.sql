-- Reasoning tokens are billed like output tokens but reported separately by Gemini.
ALTER TABLE extraction ADD COLUMN thinking_tokens INT;
