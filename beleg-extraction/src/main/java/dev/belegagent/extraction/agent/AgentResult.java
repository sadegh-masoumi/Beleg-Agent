package dev.belegagent.extraction.agent;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.accounting.AccountSuggestion;
import java.util.List;

/**
 * Result of {@link BelegAgent#process}: every attempt (kept for GoBD and evals), the chosen one, the account
 * suggestion for the chosen data and the decision.
 *
 * @param chosen    index into {@code attempts} of the attempt whose data is used
 * @param notes     routing and fallback notes for the audit trail, e.g. why the e-invoice route was not taken
 */
public record AgentResult(List<Attempt> attempts, int chosen, AccountSuggestion account, Decision decision, List<String> notes) {

    public AgentResult {
        attempts = List.copyOf(attempts);
        notes = List.copyOf(notes);
    }

    public Attempt chosenAttempt() {
        return attempts.get(chosen);
    }

    public Beleg beleg() {
        return chosenAttempt().outcome().beleg();
    }

    public boolean selfCorrected() {
        return chosenAttempt().source() == Source.MODEL_CORRECTION;
    }
}
