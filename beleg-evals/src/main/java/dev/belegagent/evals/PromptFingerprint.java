package dev.belegagent.evals;

import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.llm.ExtractedBeleg;
import dev.belegagent.extraction.llm.ExtractedPosition;
import dev.belegagent.extraction.llm.ExtractedTaxLine;
import dev.langchain4j.model.output.structured.Description;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;

/**
 * Short hash of everything that changes the model's input besides the document: the system prompt and the
 * schema (field names and descriptions). Part of the cache key, so a prompt change never reuses old answers,
 * and printed in the report so results stay traceable to a prompt version.
 */
final class PromptFingerprint {

    private PromptFingerprint() {
    }

    static String compute() {
        StringBuilder sb = new StringBuilder(prompt());
        for (Class<?> type : new Class<?>[]{ExtractedBeleg.class, ExtractedPosition.class, ExtractedTaxLine.class}) {
            sb.append('\n').append(type.getSimpleName());
            for (RecordComponent c : type.getRecordComponents()) {
                // @Description targets FIELD, so on a record component it lands on the private field
                Description d = field(type, c.getName()).getAnnotation(Description.class);
                sb.append('|').append(c.getName()).append(':').append(c.getType().getSimpleName())
                        .append(':').append(d == null ? "" : String.join(" ", d.value()));
            }
        }
        return ZugferdDataset.sha256(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    }

    /** Fingerprint of the self-correction input: the extraction fingerprint plus the correction instruction. */
    static String computeCorrection() {
        String input = compute() + "\n" + BelegExtractionService.correctionTemplate();
        return ZugferdDataset.sha256(input.getBytes(StandardCharsets.UTF_8)).substring(0, 10);
    }

    private static String prompt() {
        try (InputStream in = ExtractedBeleg.class.getResourceAsStream("/prompts/extract-beleg-system.txt")) {
            if (in == null) {
                throw new IllegalStateException("system prompt resource missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
