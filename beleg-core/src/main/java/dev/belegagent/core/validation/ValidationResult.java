package dev.belegagent.core.validation;

import java.util.List;

/**
 * Outcome of {@link BelegValidator#validate}. A document is {@link #valid()} when no {@link Severity#ERROR} remains;
 * warnings are informational.
 */
public record ValidationResult(RuleSet ruleSet, List<Finding> findings) {

    public ValidationResult {
        findings = List.copyOf(findings);
    }

    public boolean valid() {
        return errors().isEmpty();
    }

    public List<Finding> errors() {
        return findings.stream().filter(f -> f.severity() == Severity.ERROR).toList();
    }

    public List<Finding> warnings() {
        return findings.stream().filter(f -> f.severity() == Severity.WARNING).toList();
    }
}
