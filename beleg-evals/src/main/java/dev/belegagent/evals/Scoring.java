package dev.belegagent.evals;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Metrics from the plan:
 * <ul>
 *   <li>IDs and dates: exact match after normalization (whitespace, case)</li>
 *   <li>Amounts: fixed 1 cent tolerance, no partial credit (ANLS is misleading for prices)</li>
 *   <li>Seller name: ANLS, similarity below 0.5 counts as zero</li>
 *   <li>Line items: F1 over rows (a row matches on amount ±1 cent and a matching description, see
 *       {@link #descriptionSimilarity})</li>
 * </ul>
 * Fields without ground truth are not scored.
 */
final class Scoring {

    static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    enum Field {
        INVOICE_NUMBER, ISSUE_DATE, SELLER_NAME, SELLER_VAT_ID, CURRENCY, NET_TOTAL, TAX_TOTAL, GROSS_TOTAL, AMOUNT_DUE,
        /** Net and tax per rate (§14 Abs. 4 Nr. 7/8 UStG, needed to book input tax per rate); all rates must match. */
        VAT_BREAKDOWN
    }

    /** Fields needed to book the document; "key fields correct" means all of these are exact. */
    static final List<Field> KEY_FIELDS =
            List.of(Field.INVOICE_NUMBER, Field.ISSUE_DATE, Field.NET_TOTAL, Field.TAX_TOTAL, Field.GROSS_TOTAL);

    /**
     * @param fields  score per scored field, 0..1
     * @param lineF1  F1 of line items; {@code null} if the truth has no lines
     */
    record DocumentScore(Map<Field, Double> fields, Double lineF1, boolean keyFieldsCorrect) {
    }

    private Scoring() {
    }

    static DocumentScore score(Beleg truth, Beleg predicted) {
        Map<Field, Double> s = new EnumMap<>(Field.class);
        if (predicted == null) {
            predicted = new Beleg(null, null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(), null, null, null, null, null);
        }
        exactText(s, Field.INVOICE_NUMBER, truth.invoiceNumber(), predicted.invoiceNumber());
        exactDate(s, Field.ISSUE_DATE, truth.issueDate(), predicted.issueDate());
        if (truth.sellerName() != null) {
            s.put(Field.SELLER_NAME, anls(truth.sellerName(), predicted.sellerName()));
        }
        exactText(s, Field.SELLER_VAT_ID, truth.sellerVatId(), predicted.sellerVatId());
        exactText(s, Field.CURRENCY, truth.currency(), predicted.currency());
        amount(s, Field.NET_TOTAL, truth.netTotal(), predicted.netTotal());
        amount(s, Field.TAX_TOTAL, truth.taxTotal(), predicted.taxTotal());
        amount(s, Field.GROSS_TOTAL, truth.grossTotal(), predicted.grossTotal());
        // amount due: a missing value means "same as gross" on most documents
        if (truth.amountDue() != null) {
            BigDecimal due = predicted.amountDue() != null ? predicted.amountDue() : predicted.grossTotal();
            s.put(Field.AMOUNT_DUE, amountsMatch(truth.amountDue(), due) ? 1.0 : 0.0);
        }

        if (!truth.taxLines().isEmpty()) {
            s.put(Field.VAT_BREAKDOWN, vatBreakdownMatches(truth.taxLines(), predicted.taxLines()) ? 1.0 : 0.0);
        }

        boolean keyCorrect = KEY_FIELDS.stream().allMatch(f -> !s.containsKey(f) || s.get(f) == 1.0);
        Double f1 = truth.positions().isEmpty() ? null : lineF1(truth.positions(), predicted.positions());
        return new DocumentScore(s, f1, keyCorrect);
    }

    static double anls(String truth, String predicted) {
        if (predicted == null) {
            return 0.0;
        }
        String a = normalizeText(truth);
        String b = normalizeText(predicted);
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        double similarity = 1.0 - (double) levenshtein(a, b) / max;
        return similarity < 0.5 ? 0.0 : similarity;
    }

    /**
     * The printed item text often contains the product name plus a free-text description, while the XML
     * {@code Name} holds only the name. A prediction that contains the name, or whose first line matches it,
     * read the page correctly and must not be penalised.
     */
    static double descriptionSimilarity(String truth, String predicted) {
        if (predicted == null) {
            return 0.0;
        }
        String t = normalizeText(truth);
        String p = normalizeText(predicted);
        if (!t.isEmpty() && p.contains(t)) {
            return 1.0;
        }
        String firstLine = predicted.strip().lines().findFirst().orElse("");
        return Math.max(anls(truth, predicted), anls(truth, firstLine));
    }

    /** Same set of rates, and for each rate net and tax within 1 cent. */
    static boolean vatBreakdownMatches(List<TaxLine> truth, List<TaxLine> predicted) {
        if (truth.size() != predicted.size()) {
            return false;
        }
        for (TaxLine t : truth) {
            boolean found = predicted.stream().anyMatch(p -> p.ratePercent() != null && t.ratePercent() != null
                    && p.ratePercent().compareTo(t.ratePercent()) == 0
                    && amountsMatch(t.net(), p.net()) && amountsMatch(t.tax(), p.tax()));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    static boolean amountsMatch(BigDecimal truth, BigDecimal predicted) {
        return truth != null && predicted != null && truth.subtract(predicted).abs().compareTo(TOLERANCE) <= 0;
    }

    static double lineF1(List<Position> truth, List<Position> predicted) {
        if (predicted.isEmpty()) {
            return 0.0;
        }
        List<Position> unmatched = new ArrayList<>(predicted);
        int matched = 0;
        for (Position t : truth) {
            Position best = null;
            double bestSim = -1;
            for (Position p : unmatched) {
                if (!amountsMatch(t.lineTotal(), p.lineTotal())) {
                    continue;
                }
                double sim = t.description() == null ? 1.0 : descriptionSimilarity(t.description(), p.description());
                if (sim >= 0.5 && sim > bestSim) {
                    best = p;
                    bestSim = sim;
                }
            }
            if (best != null) {
                unmatched.remove(best);
                matched++;
            }
        }
        if (matched == 0) {
            return 0.0;
        }
        double precision = (double) matched / predicted.size();
        double recall = (double) matched / truth.size();
        return 2 * precision * recall / (precision + recall);
    }

    private static void exactText(Map<Field, Double> s, Field field, String truth, String predicted) {
        if (truth != null) {
            s.put(field, predicted != null && normalizeId(truth).equals(normalizeId(predicted)) ? 1.0 : 0.0);
        }
    }

    private static void exactDate(Map<Field, Double> s, Field field, LocalDate truth, LocalDate predicted) {
        if (truth != null) {
            s.put(field, Objects.equals(truth, predicted) ? 1.0 : 0.0);
        }
    }

    private static void amount(Map<Field, Double> s, Field field, BigDecimal truth, BigDecimal predicted) {
        if (truth != null) {
            s.put(field, amountsMatch(truth, predicted) ? 1.0 : 0.0);
        }
    }

    private static String normalizeId(String value) {
        return value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeText(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
