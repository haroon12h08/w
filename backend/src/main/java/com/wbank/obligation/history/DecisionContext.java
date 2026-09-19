package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A historical credit decision, reconstructed: evidence → decision → action → outcome.
 *
 * <p>Everything under "context" is limited to what was known at {@code decidedAt}. The
 * {@code outcome} section is explicitly AFTER the decision, bounded by {@code outcomeKnownAt},
 * and is never mixed into the context.
 *
 * @param snapshot           content captured at decision time (null for decisions made before V10)
 * @param snapshotVerified   the stored content still matches the hash taken at decision time
 * @param loanBeforeDecision the subject loan reconstructed from history immediately before the decision
 * @param borrowerAtDecision the debtor's other loans reconstructed from history as known at decision time
 * @param laterCorrections   corrections to this decision's information recorded after it (never applied silently)
 */
public record DecisionContext(UUID decisionId, UUID obligationId, String kind, Instant decidedAt, String decidedBy,
                              String policyCode, Integer policyVersion, JsonNode policyRules,
                              JsonNode snapshot, String snapshotSha256, boolean snapshotVerified,
                              HistoricalLoanState loanBeforeDecision, List<HistoricalLoanState> borrowerAtDecision,
                              String resultingStatus, List<HistoricalLoanState.Correction> laterCorrections,
                              Outcome outcome) {

    public record Outcome(Instant outcomeKnownAt, String statusAsKnown, long maxDaysPastDue, boolean defaulted,
                          boolean settled, boolean settledEarly, long principalRepaidMinor, long interestRepaidMinor,
                          List<TraceEvent> eventsAfterDecision) {}

    public record TraceEvent(int loanSeq, String type, Instant effectiveAt, Instant recordedAt, JsonNode payload) {}
}
