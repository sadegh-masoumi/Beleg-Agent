package dev.belegagent.core.accounting;

import java.util.List;
import java.util.Map;

/**
 * Suggested booking for one document. Always a suggestion: the accountant or reviewer confirms it.
 *
 * @param account           expense account in the chosen chart
 * @param inputTaxAccounts  Vorsteuer account per tax rate in percent ("19" → 1576 in SKR03); rates without a standard account are absent
 * @param confidence        HIGH if the seller name matched, MEDIUM if only line items matched, LOW for the fallback
 * @param matchedKeywords   keywords that decided the category (empty for the fallback)
 * @param note              hint for the reviewer, e.g. the Bewirtung deduction rule; {@code null} if none
 */
public record AccountSuggestion(
        ChartOfAccounts chart,
        ExpenseCategory category,
        String account,
        String accountName,
        Map<String, String> inputTaxAccounts,
        Confidence confidence,
        List<String> matchedKeywords,
        String note) {

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public AccountSuggestion {
        inputTaxAccounts = Map.copyOf(inputTaxAccounts);
        matchedKeywords = List.copyOf(matchedKeywords);
    }
}
