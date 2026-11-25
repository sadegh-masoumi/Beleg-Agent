package dev.belegagent.extraction;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

import dev.belegagent.core.validation.Finding;
import dev.belegagent.extraction.llm.BelegAiService;
import dev.belegagent.extraction.llm.BelegMapper;
import dev.belegagent.extraction.llm.ExtractedBeleg;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GeminiMediaResolutionLevel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Vision-model extraction: image or PDF in, {@link ExtractionOutcome} out.
 * Pure Java (no Spring) so the app and the eval harness use exactly the same code path.
 */
public final class BelegExtractionService implements BelegExtractor {

    private static final String CORRECTION_TEMPLATE = resource("/prompts/correct-beleg-user.txt");

    private final BelegAiService aiService;
    private final String modelName;
    private final PageRenderer renderer;

    public BelegExtractionService(ChatModel chatModel, String modelName, PageRenderer renderer) {
        this.aiService = AiServices.builder(BelegAiService.class).chatModel(chatModel).build();
        this.modelName = modelName;
        this.renderer = renderer;
    }

    public static BelegExtractionService gemini(GeminiSettings settings, PageRenderer renderer) {
        return new BelegExtractionService(geminiChatModel(settings), settings.modelName(), renderer);
    }

    static ChatModel geminiChatModel(GeminiSettings s) {
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(s.apiKey())
                .modelName(s.modelName())
                .temperature(0.0)
                .timeout(s.timeout())
                .maxRetries(s.maxRetries())
                // Without this LangChain4j silently falls back to prompt-only JSON.
                .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA);
        if (s.mediaResolution() != null && !s.mediaResolution().isBlank()) {
            builder.mediaResolution(GeminiMediaResolutionLevel.valueOf(
                    "MEDIA_RESOLUTION_" + s.mediaResolution().strip().toUpperCase(Locale.ROOT)));
        }
        if (s.thinkingLevel() != null && !s.thinkingLevel().isBlank()) {
            builder.thinkingConfig(GeminiThinkingConfig.builder()
                    .thinkingLevel(GeminiThinkingConfig.GeminiThinkingLevel.valueOf(
                            s.thinkingLevel().strip().toUpperCase(Locale.ROOT)))
                    .build());
        }
        return builder.build();
    }

    @Override
    public ExtractionOutcome extract(byte[] content, FileKind kind) {
        return call(content, kind, null);
    }

    @Override
    public ExtractionOutcome correct(byte[] content, FileKind kind, ExtractionOutcome previous, List<Finding> errors) {
        String findings = errors.stream()
                .map(f -> "- " + f.code() + " (" + f.field() + "): " + f.message())
                .collect(Collectors.joining("\n"));
        String previousAnswer = previous.rawResponse() != null ? previous.rawResponse() : previous.beleg().toString();
        String instruction = CORRECTION_TEMPLATE
                .replace("{{errors}}", findings)
                .replace("{{previous}}", previousAnswer);
        return call(content, kind, instruction);
    }

    /** The correction instruction (template from {@code /prompts/correct-beleg-user.txt}), for eval fingerprints. */
    public static String correctionTemplate() {
        return CORRECTION_TEMPLATE;
    }

    private ExtractionOutcome call(byte[] content, FileKind kind, String correction) {
        List<byte[]> images;
        String imageMime;
        int totalPages;
        if (kind == FileKind.PDF) {
            PageRenderer.RenderedPages pages = renderer.render(content);
            if (pages.pngPages().isEmpty()) {
                throw new ExtractionException("PDF has no pages");
            }
            images = pages.pngPages();
            imageMime = FileKind.PNG.mimeType();
            totalPages = pages.totalPages();
        } else {
            images = List.of(content);
            imageMime = kind.mimeType();
            totalPages = 1;
        }

        List<Content> contents = new ArrayList<>();
        if (correction != null) {
            contents.add(TextContent.from(correction));
        }
        contents.add(TextContent.from(images.size() == 1
                ? "Extract the data from this document."
                : "Extract the data from this document. The %d images are consecutive pages of the same document."
                        .formatted(images.size())));
        Base64.Encoder base64 = Base64.getEncoder();
        for (byte[] image : images) {
            contents.add(ImageContent.from(base64.encodeToString(image), imageMime));
        }

        long start = System.nanoTime();
        Result<ExtractedBeleg> result;
        try {
            result = aiService.extract(contents);
        } catch (RuntimeException e) {
            throw new ExtractionException("Model call failed (" + modelName + "): " + e.getMessage(), e);
        }
        Duration latency = Duration.ofNanos(System.nanoTime() - start);

        ExtractedBeleg extracted = result.content();
        if (extracted == null) {
            throw new ExtractionException("Model returned no structured content (" + modelName + ")");
        }
        TokenUsage usage = result.tokenUsage();
        String raw = result.finalResponse() != null && result.finalResponse().aiMessage() != null
                ? result.finalResponse().aiMessage().text()
                : null;

        return new ExtractionOutcome(
                BelegMapper.toBeleg(extracted),
                raw,
                modelName,
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                usage instanceof GoogleAiGeminiTokenUsage gemini ? gemini.thoughtsTokenCount() : null,
                latency,
                images.size(),
                totalPages);
    }

    private static String resource(String name) {
        try (InputStream in = BelegExtractionService.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Resource missing: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
