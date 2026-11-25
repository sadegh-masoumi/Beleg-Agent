package dev.belegagent.evals;

import dev.belegagent.core.validation.BelegValidator;
import dev.belegagent.core.validation.ValidationResult;
import dev.belegagent.evals.Scoring.DocumentScore;
import dev.belegagent.evals.Scoring.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Aggregates scored predictions per config and renders the Markdown report. */
final class Report {

    /**
     * One document of one config.
     *
     * @param prediction      first model answer (the Phase 2 baseline)
     * @param validation      validator result of the first answer; {@code null} if the call failed
     * @param corrected       self-correction answer; {@code null} if none was made
     * @param finalScore      score of the attempt the agent chose (equals {@code score} without correction)
     * @param correctionError message if the correction call failed
     */
    record Row(EvalCase evalCase, Prediction prediction, DocumentScore score, ValidationResult validation,
               Prediction corrected, DocumentScore finalScore, ValidationResult finalValidation, String correctionError) {
    }

    /**
     * Phase 3 metrics. "Flagged" = the validator rejected the first answer.
     *
     * @param acceptedFirst        share of documents whose first answer passed the validator
     * @param flaggedWrong         rejected and key fields wrong (errors caught)
     * @param flaggedRight         rejected although key fields were right (false alarms, e.g. a non-key field)
     * @param unflaggedWrong       accepted although key fields were wrong (errors the rules cannot see)
     * @param finalKeyFieldsCorrect key-field accuracy of the chosen attempt (after self-correction)
     * @param acceptedFinal        share of documents that pass after self-correction (1 − review rate)
     */
    record ValidationStats(
            double acceptedFirst,
            int flaggedWrong,
            int flaggedRight,
            int unflaggedWrong,
            int corrections,
            int correctionFailures,
            double finalKeyFieldsCorrect,
            Double vatBreakdownFirst,
            Double vatBreakdownFinal,
            double acceptedFinal,
            Double correctionCostPer1000Usd,
            Map<String, Integer> firstErrorCodes) {
    }

    record ConfigSummary(
            EvalConfig config,
            int docs,
            int errors,
            double keyFieldsCorrect,
            double fieldAccuracy,
            Double lineF1,
            Map<Field, Double> perField,
            long p50LatencyMs,
            long p95LatencyMs,
            double avgInputTokens,
            double avgOutputTokens,
            double avgThinkingTokens,
            Double costPer1000Usd,
            ValidationStats validation) {
    }

    private Report() {
    }

    static ConfigSummary summarize(EvalConfig config, List<Row> rows, Pricing pricing) {
        int errors = (int) rows.stream().filter(r -> r.prediction().error() != null).count();
        double key = rows.stream().filter(r -> r.score().keyFieldsCorrect()).count() / (double) rows.size();

        Map<Field, Double> perField = new java.util.EnumMap<>(Field.class);
        double sum = 0;
        int count = 0;
        for (Field f : Field.values()) {
            List<Double> values = rows.stream().map(r -> r.score().fields().get(f)).filter(Objects::nonNull).toList();
            if (!values.isEmpty()) {
                perField.put(f, values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
                sum += values.stream().mapToDouble(Double::doubleValue).sum();
                count += values.size();
            }
        }
        OptionalDouble f1 = rows.stream().map(r -> r.score().lineF1()).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).average();

        List<Row> ok = rows.stream().filter(r -> r.prediction().error() == null).toList();
        long[] latencies = ok.stream().mapToLong(r -> r.prediction().latencyMs()).sorted().toArray();
        double in = ok.stream().mapToDouble(r -> nz(r.prediction().inputTokens())).average().orElse(0);
        double out = ok.stream().mapToDouble(r -> nz(r.prediction().outputTokens())).average().orElse(0);
        double think = ok.stream().mapToDouble(r -> nz(r.prediction().thinkingTokens())).average().orElse(0);
        Double cost = ok.isEmpty() ? null
                : pricing.costUsd(config.model(), in, out + think).map(c -> c * 1000).orElse(null);

        return new ConfigSummary(config, rows.size(), errors, key, count == 0 ? 0 : sum / count,
                f1.isPresent() ? f1.getAsDouble() : null, perField,
                percentile(latencies, 0.50), percentile(latencies, 0.95), in, out, think, cost,
                validationStats(rows, pricing));
    }

    static ValidationStats validationStats(List<Row> rows, Pricing pricing) {
        int n = rows.size();
        int accepted = 0;
        int flaggedWrong = 0;
        int flaggedRight = 0;
        int unflaggedWrong = 0;
        int acceptedFinal = 0;
        int corrections = 0;
        int failures = 0;
        double correctionCost = 0;
        boolean costKnown = true;
        Map<String, Integer> codes = new TreeMap<>();
        for (Row r : rows) {
            if (r.validation() == null) {
                continue;
            }
            boolean valid = r.validation().valid();
            boolean right = r.score().keyFieldsCorrect();
            if (valid) {
                accepted++;
                unflaggedWrong += right ? 0 : 1;
            } else {
                flaggedWrong += right ? 0 : 1;
                flaggedRight += right ? 1 : 0;
                r.validation().errors().forEach(f -> codes.merge(f.code(), 1, Integer::sum));
            }
            if (r.finalValidation() != null && r.finalValidation().valid()) {
                acceptedFinal++;
            }
            if (r.correctionError() != null) {
                failures++;
            }
            if (r.corrected() != null) {
                corrections++;
                Prediction c = r.corrected();
                Optional<Double> cost = pricing.costUsd(c.model(), nz(c.inputTokens()),
                        nz(c.outputTokens()) + nz(c.thinkingTokens()));
                if (cost.isPresent()) {
                    correctionCost += cost.get();
                } else {
                    costKnown = false;
                }
            }
        }
        double finalKey = rows.stream().filter(r -> r.finalScore().keyFieldsCorrect()).count() / (double) n;
        OptionalDouble vatFirst = rows.stream().map(r -> r.score().fields().get(Field.VAT_BREAKDOWN))
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        OptionalDouble vatFinal = rows.stream().map(r -> r.finalScore().fields().get(Field.VAT_BREAKDOWN))
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average();
        return new ValidationStats(accepted / (double) n, flaggedWrong, flaggedRight, unflaggedWrong, corrections,
                failures, finalKey, vatFirst.isPresent() ? vatFirst.getAsDouble() : null,
                vatFinal.isPresent() ? vatFinal.getAsDouble() : null, acceptedFinal / (double) n,
                costKnown ? correctionCost / n * 1000 : null, codes);
    }

    static String markdown(List<ConfigSummary> summaries, Map<String, List<Row>> rowsByConfig, List<EvalCase> cases,
                           BelegValidator validator, String strongModel, Pricing pricing, String fingerprint,
                           String datasetDescription) {
        int datasetSize = cases.size();
        StringBuilder md = new StringBuilder();
        md.append("# Eval results\n\n");
        md.append("Generated by `beleg-evals` on ").append(LocalDate.now()).append(". ")
                .append(datasetSize).append(" documents: ").append(datasetDescription).append("\n\n");
        md.append("Ground truth is the XML embedded in each PDF (ZUGFeRD trick); the model only sees the pages rendered at 200 dpi (max. 3 pages). ")
                .append("Prices: ").append(pricing.source()).append(" (paid tier, standard). ")
                .append("Prompt/schema fingerprint `").append(fingerprint).append("`.\n\n");

        md.append("## Summary\n\n");
        md.append("| Config | Model | Image resolution | Key fields correct | Field accuracy | Line-item F1 | Errors | Latency p50 / p95 | Tokens in / out / thinking | Cost per 1000 docs |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ConfigSummary s : summaries) {
            md.append("| `").append(s.config().name()).append("` | ").append(s.config().model()).append(" | ")
                    .append(s.config().mediaResolution()).append(" | **").append(pct(s.keyFieldsCorrect())).append("** | ")
                    .append(pct(s.fieldAccuracy())).append(" | ")
                    .append(s.lineF1() == null ? "–" : String.format(Locale.ROOT, "%.3f", s.lineF1())).append(" | ")
                    .append(s.errors()).append("/").append(s.docs()).append(" | ")
                    .append(String.format(Locale.ROOT, "%.1f s / %.1f s", s.p50LatencyMs() / 1000.0, s.p95LatencyMs() / 1000.0)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.0f / %.0f / %.0f", s.avgInputTokens(), s.avgOutputTokens(), s.avgThinkingTokens())).append(" | ")
                    .append(s.costPer1000Usd() == null ? "–" : String.format(Locale.ROOT, "$%.2f", s.costPer1000Usd())).append(" |\n");
        }
        md.append("\n*Key fields correct* = invoice number, issue date, net, tax and gross total all exact (the fields needed to book the document). ")
                .append("This table is the single-call baseline (first model answer, no self-correction).\n\n");

        validationSection(md, summaries, rowsByConfig, strongModel);
        groundTruthSection(md, cases, validator);

        md.append("## Accuracy per field\n\n| Field |");
        summaries.forEach(s -> md.append(" `").append(s.config().name()).append("` |"));
        md.append("\n|---|");
        summaries.forEach(s -> md.append("---|"));
        md.append("\n");
        for (Field f : Field.values()) {
            md.append("| ").append(f.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .append(f == Field.SELLER_NAME ? " (ANLS)" : "").append(" |");
            for (ConfigSummary s : summaries) {
                Double v = s.perField().get(f);
                md.append(" ").append(v == null ? "–" : pct(v)).append(" |");
            }
            md.append("\n");
        }

        md.append("\n## Mismatches\n\nUp to 15 per config, for debugging. Expected = XML ground truth.\n\n");
        md.append("| Config | Document | Field | Expected | Got |\n|---|---|---|---|---|\n");
        for (ConfigSummary s : summaries) {
            List<String[]> lines = mismatches(rowsByConfig.get(s.config().name()));
            lines.stream().limit(15).forEach(l -> md.append("| `").append(s.config().name()).append("` | ")
                    .append(l[0]).append(" | ").append(l[1]).append(" | ").append(l[2]).append(" | ").append(l[3]).append(" |\n"));
        }

        md.append("""

                ## Caveats

                - The corpus consists of sample invoices from standardisation bodies and vendors, not real-world scans or photos.
                  Real receipts (crumpled, photographed) will score lower; those datasets follow.
                - Where a PDF's visual layout and its XML disagree (e.g. gross line prices printed, net in XML), the model is
                  penalised although it read the page correctly. Mismatches above should be checked with that in mind.
                - Self-correction can only help where the deterministic rules notice a problem. Misreadings that keep the
                  sums consistent (e.g. a wrong invoice number) pass the validator; see "accepted but key fields wrong".
                - Seller name: some XMLs put routing data into the name (e.g. "GLN 4333741000005 Lief-Nr: 549910 MUSTERLIEFERANT GMBH")
                  while the page prints only "MUSTERLIEFERANT GMBH"; the model is right, the ANLS score is lowered anyway.
                - gemini-3.8-flash is not in the model comparison: its free tier allows 5 requests/minute and 20/day.
                  On 2026-11-23 it also answered HTTP 503 ("high demand"), so self-correction with it could not be measured yet;
                  the run above uses the cheap model as its own corrector, which isolates the effect of the validator feedback.
                """);
        return md.toString();
    }

    private static void validationSection(StringBuilder md, List<ConfigSummary> summaries,
                                          Map<String, List<Row>> rowsByConfig, String strongModel) {
        md.append("## Validation and self-correction\n\n");
        md.append("The deterministic validator (§14 UStG / §33 UStDV mandatory fields, tax per rate with 1 cent tolerance, sums) ")
                .append("checks every first answer. ");
        if (strongModel == null) {
            md.append("Self-correction was **off** in this run (`--self-correct` not set), so *final* equals the first answer.\n\n");
        } else {
            md.append("Rejected answers were sent back once to `").append(strongModel)
                    .append("` with the validator's messages; the attempt with fewer errors is kept.\n\n");
        }
        md.append("| Config | Validator accepts 1st answer | Rejected, key fields wrong (caught) | Rejected, key fields right | Accepted, key fields wrong (missed) | Self-corrections | Key fields correct: 1st → final | VAT breakdown correct: 1st → final | Needs review (final) | Extra cost per 1000 docs |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (ConfigSummary s : summaries) {
            ValidationStats v = s.validation();
            md.append("| `").append(s.config().name()).append("` | ").append(pct(v.acceptedFirst())).append(" | ")
                    .append(v.flaggedWrong()).append(" | ").append(v.flaggedRight()).append(" | ")
                    .append(v.unflaggedWrong()).append(" | ")
                    .append(v.corrections()).append(v.correctionFailures() > 0 ? " (" + v.correctionFailures() + " failed)" : "")
                    .append(" | ").append(pct(s.keyFieldsCorrect())).append(" → **").append(pct(v.finalKeyFieldsCorrect()))
                    .append("** | ").append(v.vatBreakdownFirst() == null ? "–" : pct(v.vatBreakdownFirst())).append(" → **")
                    .append(v.vatBreakdownFinal() == null ? "–" : pct(v.vatBreakdownFinal())).append("** | ")
                    .append(pct(1 - v.acceptedFinal())).append(" | ")
                    .append(v.correctionCostPer1000Usd() == null ? "–" : String.format(Locale.ROOT, "$%.2f", v.correctionCostPer1000Usd()))
                    .append(" |\n");
        }
        md.append("\n*Rejected, key fields right*: the booking totals are right but something else is inconsistent, ")
                .append("usually the VAT breakdown per rate (net, tax, gross per rate do not add up). Since the ground truth ")
                .append("passes all rules (next section), every rejection points at a real error in the answer.\n");
        md.append("\nValidator errors on first answers: ");
        md.append(String.join("; ", summaries.stream()
                .map(s -> "`" + s.config().name() + "` " + (s.validation().firstErrorCodes().isEmpty() ? "none"
                        : s.validation().firstErrorCodes().entrySet().stream()
                                .map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining(", "))))
                .toList()));
        md.append(".\n\n");
        List<String> failures = new ArrayList<>();
        for (ConfigSummary s : summaries) {
            for (Row r : rowsByConfig.get(s.config().name())) {
                if (r.correctionError() != null) {
                    failures.add("`" + s.config().name() + "` " + shortId(r.evalCase().id()) + ": "
                            + escape(r.correctionError().replace("Self-correction failed, keeping the first attempt: ", "")));
                }
            }
        }
        if (!failures.isEmpty()) {
            md.append("Failed self-correction calls (the first attempt was kept and the document goes to review):\n\n");
            failures.forEach(f -> md.append("- ").append(f).append("\n"));
            md.append("\n");
        }
    }

    /** Calibration: the rules applied to the XML ground truth. Errors here are false alarms of the rules or defects of the samples. */
    private static void groundTruthSection(StringBuilder md, List<EvalCase> cases, BelegValidator validator) {
        Map<String, List<String>> byCode = new TreeMap<>();
        int pass = 0;
        for (EvalCase c : cases) {
            ValidationResult result = validator.validate(c.truth());
            if (result.valid()) {
                pass++;
            }
            result.errors().forEach(f -> byCode.computeIfAbsent(f.code(), k -> new ArrayList<>()).add(shortId(c.id())));
        }
        md.append("## Validator on the ground truth\n\n");
        md.append("Calibration of the rules: the validator applied to the embedded XML itself. ")
                .append(pass).append(" of ").append(cases.size()).append(" documents pass. ")
                .append("Errors on correct data are false alarms of the rules or real defects of the sample invoices; ")
                .append("they bound the achievable acceptance rate.\n\n");
        if (!byCode.isEmpty()) {
            md.append("| Error | Documents |\n|---|---|\n");
            byCode.forEach((code, docs) -> md.append("| ").append(code).append(" | ").append(docs.size()).append(": ")
                    .append(String.join(", ", docs.stream().limit(4).toList()).replace("|", "\\|"))
                    .append(docs.size() > 4 ? ", +" + (docs.size() - 4) + " more" : "").append(" |\n"));
            md.append("\n");
        }
    }

    private static List<String[]> mismatches(List<Row> rows) {
        List<String[]> out = new ArrayList<>();
        for (Row r : rows) {
            String doc = shortId(r.evalCase().id());
            if (r.prediction().error() != null) {
                out.add(new String[]{doc, "error", "–", escape(r.prediction().error())});
                continue;
            }
            for (var e : r.score().fields().entrySet()) {
                if (e.getValue() < 1.0 && e.getKey() != Field.SELLER_NAME || e.getValue() == 0.0) {
                    out.add(new String[]{doc, e.getKey().name().toLowerCase(Locale.ROOT),
                            escape(value(e.getKey(), r.evalCase().truth())), escape(value(e.getKey(), r.prediction().beleg()))});
                }
            }
        }
        return out;
    }

    private static String value(Field f, dev.belegagent.core.Beleg b) {
        if (b == null) {
            return "null";
        }
        Object v = switch (f) {
            case INVOICE_NUMBER -> b.invoiceNumber();
            case ISSUE_DATE -> b.issueDate();
            case SELLER_NAME -> b.sellerName();
            case SELLER_VAT_ID -> b.sellerVatId();
            case CURRENCY -> b.currency();
            case NET_TOTAL -> b.netTotal();
            case TAX_TOTAL -> b.taxTotal();
            case GROSS_TOTAL -> b.grossTotal();
            case AMOUNT_DUE -> b.amountDue() != null ? b.amountDue() : b.grossTotal();
            case VAT_BREAKDOWN -> b.taxLines().stream()
                    .map(t -> (t.ratePercent() == null ? "?" : t.ratePercent().stripTrailingZeros().toPlainString())
                            + "%: " + t.net() + " + " + t.tax())
                    .collect(Collectors.joining("; "));
        };
        return String.valueOf(v);
    }

    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    private static String escape(String s) {
        String v = s == null ? "null" : s.replace("|", "\\|").replace("\n", " ");
        return v.length() > 80 ? v.substring(0, 77) + "..." : v;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f %%", v * 100);
    }

    private static double nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
