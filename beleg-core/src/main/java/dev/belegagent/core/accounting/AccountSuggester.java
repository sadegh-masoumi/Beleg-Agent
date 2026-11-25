package dev.belegagent.core.accounting;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Suggests an SKR03 / SKR04 expense account from keywords in the seller name and the line items.
 * Deterministic and explainable: the suggestion names the keywords that decided it. A seller-name hit counts
 * three times as much as a line-item hit. Without any hit the fallback is "Sonstige betriebliche Aufwendungen"
 * with low confidence, so the reviewer sees that the account must be chosen by hand.
 */
public final class AccountSuggester {

    private static final int SELLER_WEIGHT = 3;
    private static final int POSITION_WEIGHT = 1;
    /** Keywords up to this length must match a whole word ("aral", "dhl"); longer ones may be part of a compound ("Hafenrestaurant"). */
    private static final int WHOLE_WORD_MAX_LENGTH = 5;

    private static final Map<ExpenseCategory, List<String>> KEYWORDS = keywords();

    private final ChartOfAccounts chart;

    public AccountSuggester(ChartOfAccounts chart) {
        this.chart = Objects.requireNonNull(chart);
    }

    public AccountSuggestion suggest(Beleg beleg) {
        String seller = normalize(beleg.sellerName());
        List<String> lines = beleg.positions().stream().map(Position::description).map(AccountSuggester::normalize).toList();

        ExpenseCategory best = ExpenseCategory.OTHER;
        int bestScore = 0;
        boolean bestSellerHit = false;
        List<String> bestKeywords = List.of();
        for (Map.Entry<ExpenseCategory, List<String>> entry : KEYWORDS.entrySet()) {
            int score = 0;
            boolean sellerHit = false;
            List<String> matched = new ArrayList<>();
            for (String keyword : entry.getValue()) {
                Pattern pattern = pattern(keyword);
                if (pattern.matcher(seller).find()) {
                    score += SELLER_WEIGHT;
                    sellerHit = true;
                    matched.add(keyword);
                } else if (lines.stream().anyMatch(l -> pattern.matcher(l).find())) {
                    score += POSITION_WEIGHT;
                    matched.add(keyword);
                }
            }
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
                bestSellerHit = sellerHit;
                bestKeywords = matched;
            }
        }

        AccountSuggestion.Confidence confidence = bestScore == 0 ? AccountSuggestion.Confidence.LOW
                : bestSellerHit ? AccountSuggestion.Confidence.HIGH : AccountSuggestion.Confidence.MEDIUM;
        return new AccountSuggestion(chart, best, best.account(chart), best.accountName(), inputTaxAccounts(beleg),
                confidence, bestKeywords, note(best));
    }

    /** Vorsteuer accounts for the rates on the document (19 % → SKR03 1576 / SKR04 1406, 7 % → 1571 / 1401). */
    Map<String, String> inputTaxAccounts(Beleg beleg) {
        Map<String, String> accounts = new LinkedHashMap<>();
        List<BigDecimal> rates = new ArrayList<>();
        beleg.taxLines().stream().map(TaxLine::ratePercent).filter(Objects::nonNull).forEach(rates::add);
        beleg.positions().stream().map(Position::taxRatePercent).filter(Objects::nonNull).forEach(rates::add);
        for (BigDecimal rate : rates) {
            String key = rate.stripTrailingZeros().toPlainString();
            String account = switch (key) {
                case "19" -> chart == ChartOfAccounts.SKR03 ? "1576" : "1406";
                case "7" -> chart == ChartOfAccounts.SKR03 ? "1571" : "1401";
                default -> null;
            };
            if (account != null) {
                accounts.putIfAbsent(key, account);
            }
        }
        return accounts;
    }

    private String note(ExpenseCategory category) {
        return switch (category) {
            case ENTERTAINMENT -> "Bewirtung: only 70 % of the net amount is deductible; book 30 % to "
                    + (chart == ChartOfAccounts.SKR03 ? "4654" : "6644")
                    + " (nicht abzugsfähig). Occasion and participants must be documented.";
            case OTHER -> "No keyword matched; choose the account manually.";
            default -> null;
        };
    }

    private static Pattern pattern(String keyword) {
        String quoted = Pattern.quote(keyword);
        return keyword.length() <= WHOLE_WORD_MAX_LENGTH
                ? Pattern.compile("(?<![a-z0-9])" + quoted + "(?![a-z0-9])")
                : Pattern.compile(quoted);
    }

    /** Lower case, umlauts transliterated, so "Bürobedarf" and "Buerobedarf" match the same keyword. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss").replace("é", "e");
    }

    private static Map<ExpenseCategory, List<String>> keywords() {
        Map<ExpenseCategory, List<String>> m = new EnumMap<>(ExpenseCategory.class);
        m.put(ExpenseCategory.VEHICLE, List.of("tankstelle", "aral", "shell", "esso", "jet", "agip", "avia",
                "diesel", "benzin", "super e10", "super plus", "e10", "kraftstoff", "adblue", "autowaesche", "waschanlage",
                "parkhaus", "parkgebuehr", "parkschein"));
        m.put(ExpenseCategory.ENTERTAINMENT, List.of("restaurant", "gaststaette", "gasthaus", "gasthof", "ristorante",
                "trattoria", "pizzeria", "osteria", "bistro", "brauhaus", "wirtshaus", "steakhaus", "bewirtung", "cafe"));
        m.put(ExpenseCategory.ACCOMMODATION, List.of("hotel", "pension", "uebernachtung", "hostel", "gaestehaus", "airbnb"));
        m.put(ExpenseCategory.TRAVEL_FARE, List.of("deutsche bahn", "db fernverkehr", "db regio", "fahrkarte", "fahrschein",
                "flixbus", "flixtrain", "taxi", "lufthansa", "eurowings", "flugticket", "bvg", "mvg", "hvv", "rmv", "vbb"));
        m.put(ExpenseCategory.POSTAGE, List.of("deutsche post", "dhl", "porto", "briefmarke", "einschreiben", "hermes",
                "dpd", "paket"));
        m.put(ExpenseCategory.TELEPHONE, List.of("telekom", "vodafone", "telefonica", "o2", "mobilfunk", "telefon"));
        m.put(ExpenseCategory.INTERNET, List.of("internet", "dsl", "glasfaser", "webhosting", "domain"));
        m.put(ExpenseCategory.OFFICE_SUPPLIES, List.of("buerobedarf", "bueromaterial", "papier", "toner", "druckerpatrone",
                "kugelschreiber", "ordner", "briefumschlag", "staples", "viking", "mcpaper", "schreibwaren"));
        m.put(ExpenseCategory.LITERATURE, List.of("buchhandlung", "thalia", "hugendubel", "fachbuch", "fachzeitschrift"));
        m.put(ExpenseCategory.TRAINING, List.of("seminar", "schulung", "fortbildung", "weiterbildung", "workshop",
                "konferenz", "kursgebuehr"));
        m.put(ExpenseCategory.SOFTWARE, List.of("software", "lizenz", "subscription", "saas", "microsoft 365", "adobe",
                "jetbrains", "github"));
        m.put(ExpenseCategory.BANK_FEES, List.of("kontofuehrung", "bankgebuehr", "kontoentgelt", "transaktionsgebuehr"));
        return m;
    }
}
