package dev.belegagent.extraction.llm;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import java.util.List;

/**
 * LangChain4j AI service: images (plus a short text) in, {@link ExtractedBeleg} out.
 * The return type makes LangChain4j send a JSON schema; the model must declare
 * {@code RESPONSE_FORMAT_JSON_SCHEMA} or LangChain4j silently falls back to prompt-only JSON.
 */
public interface BelegAiService {

    @SystemMessage(fromResource = "/prompts/extract-beleg-system.txt")
    Result<ExtractedBeleg> extract(@UserMessage List<Content> contents);
}
