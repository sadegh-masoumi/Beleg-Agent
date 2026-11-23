package dev.belegagent.evals;

import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.GeminiSettings;
import dev.belegagent.extraction.PageRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the extraction over the eval dataset for each config and writes a Markdown report.
 *
 * <pre>
 * --data PATH               dataset root (default data/zugferd-corpus)
 * --max-docs N              maximum documents (default 40)
 * --configs a,b             presets from {@link EvalConfig#PRESETS} (default: all)
 * --delay-ms N              pause between model calls, for free-tier rate limits (default 4000)
 * --out PATH                run output and cache (default eval-results)
 * --publish PATH            also write the report there (e.g. docs/evals/results.md)
 * --min-key-accuracy X      CI gate: exit 1 if the first config's key-field accuracy is below X (0..1)
 * </pre>
 * Successful model answers are cached per config and file hash, so a rerun only pays for new or failed documents.
 */
public final class EvalRunner {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CONSECUTIVE_QUOTA_ERRORS = 3;

    private EvalRunner() {
    }

    public static void main(String[] args) {
        // Mustang logs stack traces for PDFs it cannot fully parse; the dataset stats report those cases.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "off");
        Map<String, String> a = parse(args);

        Path data = Path.of(a.getOrDefault("data", "data/zugferd-corpus"));
        int maxDocs = Integer.parseInt(a.getOrDefault("max-docs", "40"));
        long delayMs = Long.parseLong(a.getOrDefault("delay-ms", "4000"));
        Path out = Path.of(a.getOrDefault("out", "eval-results"));
        List<EvalConfig> configs = Arrays.stream(a.getOrDefault("configs", String.join(",", EvalConfig.PRESETS.keySet())).split(","))
                .map(String::strip)
                .map(name -> Optional.ofNullable(EvalConfig.PRESETS.get(name))
                        .orElseThrow(() -> new IllegalArgumentException("Unknown config " + name + "; known: " + EvalConfig.PRESETS.keySet())))
                .toList();
        String apiKey = System.getenv("GEMINI_API_KEY");

        List<EvalCase> cases = ZugferdDataset.load(data, maxDocs);
        if (cases.isEmpty()) {
            System.err.println("No eval documents found under " + data + " (run scripts/fetch-datasets.sh)");
            System.exit(2);
        }

        Pricing pricing = new Pricing();
        PageRenderer renderer = new PageRenderer(200, 3);
        String fingerprint = PromptFingerprint.compute();
        System.out.println("Prompt/schema fingerprint: " + fingerprint);
        Path cacheDir = out.resolve("cache");
        List<Report.ConfigSummary> summaries = new ArrayList<>();
        Map<String, List<Report.Row>> rowsByConfig = new LinkedHashMap<>();
        List<Prediction> allPredictions = new ArrayList<>();

        for (EvalConfig config : configs) {
            System.out.printf("%n== %s (%s, %s) ==%n", config.name(), config.model(), config.mediaResolution());
            BelegExtractionService service = null;
            List<Report.Row> rows = new ArrayList<>();
            int i = 0;
            int consecutiveQuotaErrors = 0;
            for (EvalCase c : cases) {
                i++;
                Path cacheFile = cacheDir.resolve(config.name()).resolve(fingerprint).resolve(c.sha256() + ".json");
                Prediction prediction = readCache(cacheFile);
                boolean cached = prediction != null;
                if (!cached && consecutiveQuotaErrors >= MAX_CONSECUTIVE_QUOTA_ERRORS) {
                    prediction = new Prediction(c.id(), c.sha256(), config.name(), config.model(), null, null, null,
                            null, 0, "skipped: quota exhausted (" + MAX_CONSECUTIVE_QUOTA_ERRORS + " consecutive HTTP 429)");
                } else if (!cached) {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("GEMINI_API_KEY is not set and " + c.id() + " is not cached");
                    }
                    if (service == null) {
                        service = BelegExtractionService.gemini(new GeminiSettings(apiKey, config.model(),
                                config.mediaResolution(), config.thinkingLevel(), Duration.ofSeconds(90), 3), renderer);
                    }
                    prediction = call(service, config, c);
                    if (prediction.error() == null) {
                        writeCache(cacheFile, prediction);
                        consecutiveQuotaErrors = 0;
                    } else if (prediction.error().contains("\"code\": 429")) {
                        consecutiveQuotaErrors++;
                    }
                    sleep(delayMs);
                }
                Scoring.DocumentScore score = Scoring.score(c.truth(), prediction.error() == null ? prediction.beleg() : null);
                rows.add(new Report.Row(c, prediction, score));
                allPredictions.add(prediction);
                System.out.printf("[%s] %2d/%d %-70s %s%s%n", config.name(), i, cases.size(), c.id(),
                        prediction.error() != null ? "ERROR " + prediction.error()
                                : score.keyFieldsCorrect() ? "key fields OK" : "key fields WRONG",
                        cached ? " (cached)" : String.format(Locale.ROOT, " (%.1f s)", prediction.latencyMs() / 1000.0));
            }
            rowsByConfig.put(config.name(), rows);
            summaries.add(Report.summarize(config, rows, pricing));
        }

        String markdown = Report.markdown(summaries, rowsByConfig, cases.size(), pricing, fingerprint,
                "German ZUGFeRD 2.x / XRechnung invoices (profiles BASIC, EN16931, EXTENDED, XRECHNUNG) from the "
                        + "[ZUGFeRD corpus](https://github.com/ZUGFeRD/corpus), Apache-2.0");
        Path runDir = out.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        write(runDir.resolve("report.md"), markdown);
        write(runDir.resolve("predictions.jsonl"),
                String.join("\n", allPredictions.stream().map(JSON::writeValueAsString).toList()) + "\n");
        if (a.containsKey("publish")) {
            write(Path.of(a.get("publish")), markdown);
        }

        System.out.println();
        for (Report.ConfigSummary s : summaries) {
            System.out.printf(Locale.ROOT, "%-16s key fields %5.1f %%  field acc %5.1f %%  line F1 %s  p50 %.1fs  cost/1000 %s%n",
                    s.config().name(), s.keyFieldsCorrect() * 100, s.fieldAccuracy() * 100,
                    s.lineF1() == null ? "–" : String.format(Locale.ROOT, "%.3f", s.lineF1()),
                    s.p50LatencyMs() / 1000.0, s.costPer1000Usd() == null ? "–" : String.format(Locale.ROOT, "$%.2f", s.costPer1000Usd()));
        }
        System.out.println("Report: " + runDir.resolve("report.md"));

        if (a.containsKey("min-key-accuracy")) {
            double min = Double.parseDouble(a.get("min-key-accuracy"));
            double actual = summaries.getFirst().keyFieldsCorrect();
            if (actual < min) {
                System.err.printf(Locale.ROOT, "FAIL: key-field accuracy %.3f is below threshold %.3f%n", actual, min);
                System.exit(1);
            }
            System.out.printf(Locale.ROOT, "PASS: key-field accuracy %.3f >= %.3f%n", actual, min);
        }
    }

    private static Prediction call(BelegExtractionService service, EvalConfig config, EvalCase c) {
        try {
            ExtractionOutcome o = service.extract(c.pdf(), FileKind.PDF);
            return new Prediction(c.id(), c.sha256(), config.name(), config.model(), o.beleg(), o.inputTokens(),
                    o.outputTokens(), o.thinkingTokens(), o.latency().toMillis(), null);
        } catch (RuntimeException e) {
            return new Prediction(c.id(), c.sha256(), config.name(), config.model(), null, null, null, null, 0,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static Prediction readCache(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return JSON.readValue(Files.readString(file), Prediction.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void writeCache(Path file, Prediction prediction) {
        write(file, JSON.writeValueAsString(prediction));
    }

    private static void write(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument " + arg);
            }
            String key = arg.substring(2);
            if (key.contains("=")) {
                m.put(key.substring(0, key.indexOf('=')), key.substring(key.indexOf('=') + 1));
            } else if (i + 1 < args.length) {
                m.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
        }
        return m;
    }
}
