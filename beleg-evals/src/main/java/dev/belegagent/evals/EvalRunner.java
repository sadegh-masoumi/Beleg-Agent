package dev.belegagent.evals;

import dev.belegagent.core.accounting.AccountSuggester;
import dev.belegagent.core.accounting.ChartOfAccounts;
import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.extraction.BelegExtractionService;
import dev.belegagent.extraction.BelegExtractor;
import dev.belegagent.extraction.ExtractionOutcome;
import dev.belegagent.extraction.FileKind;
import dev.belegagent.extraction.GeminiSettings;
import dev.belegagent.extraction.PageRenderer;
import dev.belegagent.extraction.agent.AgentResult;
import dev.belegagent.extraction.agent.Attempt;
import dev.belegagent.extraction.agent.BelegAgent;
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
 * --self-correct MODEL      run the agent's self-correction with MODEL (e.g. gemini-3.8-flash) on documents the
 *                           validator rejects; reports key-field accuracy with and without it
 * --min-key-accuracy X      exit 1 if the first config's key-field accuracy (after self-correction, if on) is below X
 * </pre>
 * Successful model answers are cached per config and file hash, so a rerun only pays for new or failed documents.
 */
public final class EvalRunner {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

        String strongModel = a.get("self-correct");
        String correctionFingerprint = PromptFingerprint.computeCorrection();
        BelegValidator validator = new BelegValidator();
        AccountSuggester accounts = new AccountSuggester(ChartOfAccounts.SKR03);
        if (strongModel != null) {
            System.out.println("Self-correction with " + strongModel + ", correction fingerprint " + correctionFingerprint);
        }

        for (EvalConfig config : configs) {
            System.out.printf("%n== %s (%s, %s) ==%n", config.name(), config.model(), config.mediaResolution());
            Path configCache = cacheDir.resolve(config.name()).resolve(fingerprint);
            CachingExtractor primary = new CachingExtractor(config.name(), config.model(), configCache,
                    () -> gemini(apiKey, config.model(), config, renderer), apiKey, delayMs);
            CachingExtractor corrector = strongModel == null ? null : new CachingExtractor(config.name(), strongModel,
                    configCache.resolve("correct-" + strongModel + "-" + correctionFingerprint),
                    () -> gemini(apiKey, strongModel, config, renderer), apiKey, delayMs);
            BelegAgent agent = new BelegAgent(primary, corrector != null ? corrector : primary, validator, accounts,
                    corrector != null).withoutEInvoiceRouting();

            List<Report.Row> rows = new ArrayList<>();
            int i = 0;
            for (EvalCase c : cases) {
                i++;
                Report.Row row = run(agent, config, strongModel, c);
                rows.add(row);
                allPredictions.add(row.prediction());
                if (row.corrected() != null) {
                    allPredictions.add(row.corrected());
                }
                System.out.printf("[%s] %2d/%d %-70s %s%s%s%n", config.name(), i, cases.size(), c.id(),
                        row.prediction().error() != null ? "ERROR " + row.prediction().error()
                                : row.score().keyFieldsCorrect() ? "key fields OK" : "key fields WRONG",
                        row.validation() == null ? "" : row.validation().valid() ? ", valid"
                                : ", invalid " + row.validation().errors().stream().map(f -> f.code()).toList(),
                        row.correctionError() != null ? " -> " + row.correctionError() : row.corrected() == null ? "" : " -> corrected: "
                                + (row.finalScore().keyFieldsCorrect() ? "key fields OK" : "key fields WRONG")
                                + (row.finalValidation().valid() ? ", valid" : ", still invalid"));
            }
            rowsByConfig.put(config.name(), rows);
            summaries.add(Report.summarize(config, rows, pricing));
        }

        String markdown = Report.markdown(summaries, rowsByConfig, cases, validator, strongModel, pricing, fingerprint,
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
            System.out.printf(Locale.ROOT, "%-16s key fields %5.1f %% (with self-correction %5.1f %%)  validator accepts %5.1f %%  field acc %5.1f %%  line F1 %s  p50 %.1fs  cost/1000 %s%n",
                    s.config().name(), s.keyFieldsCorrect() * 100, s.validation().finalKeyFieldsCorrect() * 100,
                    s.validation().acceptedFirst() * 100, s.fieldAccuracy() * 100,
                    s.lineF1() == null ? "–" : String.format(Locale.ROOT, "%.3f", s.lineF1()),
                    s.p50LatencyMs() / 1000.0, s.costPer1000Usd() == null ? "–" : String.format(Locale.ROOT, "$%.2f", s.costPer1000Usd()));
        }
        System.out.println("Report: " + runDir.resolve("report.md"));

        if (a.containsKey("min-key-accuracy")) {
            double min = Double.parseDouble(a.get("min-key-accuracy"));
            double actual = summaries.getFirst().validation().finalKeyFieldsCorrect();
            if (actual < min) {
                System.err.printf(Locale.ROOT, "FAIL: key-field accuracy %.3f is below threshold %.3f%n", actual, min);
                System.exit(1);
            }
            System.out.printf(Locale.ROOT, "PASS: key-field accuracy %.3f >= %.3f%n", actual, min);
        }
    }

    /**
     * Runs the agent on one document. The first attempt is the Phase 2 baseline; the chosen attempt is the result with
     * self-correction (identical to the baseline when the first answer was valid or correction is off).
     */
    private static Report.Row run(BelegAgent agent, EvalConfig config, String strongModel, EvalCase c) {
        AgentResult result;
        try {
            result = agent.process(c.pdf(), FileKind.PDF);
        } catch (RuntimeException e) {
            Prediction failed = new Prediction(c.id(), c.sha256(), config.name(), config.model(), null, null, null,
                    null, 0, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            Scoring.DocumentScore score = Scoring.score(c.truth(), null);
            return new Report.Row(c, failed, score, null, null, score, null, null);
        }
        Attempt first = result.attempts().getFirst();
        Prediction baseline = prediction(c, config.name(), first.outcome());
        Scoring.DocumentScore baselineScore = Scoring.score(c.truth(), baseline.beleg());
        Prediction corrected = result.attempts().size() > 1
                ? prediction(c, config.name() + "+correct-" + strongModel, result.attempts().get(1).outcome()) : null;
        Attempt chosen = result.chosenAttempt();
        Scoring.DocumentScore finalScore = Scoring.score(c.truth(), chosen.outcome().beleg());
        String correctionError = result.notes().stream().filter(n -> n.startsWith("Self-correction failed"))
                .findFirst().orElse(null);
        return new Report.Row(c, baseline, baselineScore, first.validation(), corrected, finalScore,
                chosen.validation(), correctionError);
    }

    private static Prediction prediction(EvalCase c, String config, ExtractionOutcome o) {
        return new Prediction(c.id(), c.sha256(), config, o.model(), o.beleg(), o.inputTokens(), o.outputTokens(),
                o.thinkingTokens(), o.latency().toMillis(), null);
    }

    private static BelegExtractor gemini(String apiKey, String model, EvalConfig config, PageRenderer renderer) {
        return BelegExtractionService.gemini(new GeminiSettings(apiKey, model, config.mediaResolution(),
                config.thinkingLevel(), Duration.ofSeconds(90), 3), renderer);
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
