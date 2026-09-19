package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.persistence.LoanDecisionRepository;
import com.wbank.platform.error.NotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconstructs the context of a historical credit decision and, separately, what happened
 * afterwards. The two halves are computed with different knowledge cut-offs, which is the
 * whole point: the context must not contain the outcome.
 */
@Service
public class DecisionReconstructionService {

    private final LoanDecisionRepository decisions;
    private final LendingEventRepository events;
    private final CreditDecisionSnapshotRepository snapshots;
    private final CreditPolicyRepository policies;
    private final DecisionSnapshots snapshotIntegrity;
    private final PointInTimeService pit;
    private final ObjectMapper json;

    public DecisionReconstructionService(LoanDecisionRepository decisions, LendingEventRepository events,
                                         CreditDecisionSnapshotRepository snapshots, CreditPolicyRepository policies,
                                         DecisionSnapshots snapshotIntegrity, PointInTimeService pit, ObjectMapper json) {
        this.decisions = decisions;
        this.events = events;
        this.snapshots = snapshots;
        this.policies = policies;
        this.snapshotIntegrity = snapshotIntegrity;
        this.pit = pit;
        this.json = json;
    }

    /**
     * @param outcomeKnownAt include the outcome as known at this instant (null: no outcome section)
     */
    @Transactional(readOnly = true)
    public DecisionContext reconstruct(UUID decisionId, Instant outcomeKnownAt) {
        LoanDecision d = decisions.findById(decisionId).orElseThrow(() -> NotFoundException.of("loan_decision", decisionId));
        LendingEvent decisionEvent = events.findBySourceTableAndSourceId("loan_decision", decisionId)
                .orElseThrow(() -> new IllegalStateException("Decision " + decisionId + " has no history event"));
        Instant t = decisionEvent.getEffectiveAt();
        List<LoanHistoryFold.Event> history = pit.history(d.getObligationId());

        // Context: strictly before the decision in this loan's order, and known by decision time.
        List<LoanHistoryFold.Event> before = history.stream()
                .filter(e -> e.loanSeq() < decisionEvent.getLoanSeq()).toList();
        HistoricalLoanState loanBefore = LoanHistoryFold.fold(d.getObligationId(), before, t, t);
        List<HistoricalLoanState> borrower = loanBefore.debtorPartyId() == null ? List.of()
                : pit.borrowerAt(loanBefore.debtorPartyId(), t, t).stream()
                        .filter(s -> !s.obligationId().equals(d.getObligationId())).toList();
        // The action is the decision event itself, which is recorded an instant after the decision
        // is taken (effective_at <= recorded_at): resolve it as known when it was recorded.
        Instant actionRecorded = decisionEvent.getRecordedAt();
        String resulting = LoanHistoryFold.fold(d.getObligationId(), history.stream()
                .filter(e -> e.loanSeq() <= decisionEvent.getLoanSeq()).toList(), actionRecorded, actionRecorded)
                .status();

        JsonNode snapshot = null;
        String sha = null;
        boolean verified = false;
        var stored = snapshots.findByDecisionId(decisionId);
        if (stored.isPresent()) {
            snapshot = read(stored.get().getContent());
            sha = stored.get().getContentSha256();
            verified = snapshotIntegrity.verify(stored.get());
        }
        JsonNode rules = d.getPolicyCode() == null ? null : policies
                .findById(new CreditPolicy.Key(d.getPolicyCode(), d.getPolicyVersion()))
                .map(p -> read(p.getRules())).orElse(null);

        Instant correctionsKnownAt = outcomeKnownAt == null ? t : outcomeKnownAt;
        List<HistoricalLoanState.Correction> corrections = LoanHistoryFold.fold(d.getObligationId(), history,
                        correctionsKnownAt, correctionsKnownAt).corrections().stream()
                .filter(c -> decisionEvent.getId().equals(c.correctsEventId())).toList();

        DecisionContext.Outcome outcome = outcomeKnownAt == null ? null
                : outcome(d.getObligationId(), history, decisionEvent.getLoanSeq(), t, outcomeKnownAt);

        return new DecisionContext(decisionId, d.getObligationId(), d.getDecision().name(), t, d.getDecidedBy(),
                d.getPolicyCode(), d.getPolicyVersion(), rules, snapshot, sha, verified, loanBefore, borrower,
                resulting, corrections, outcome);
    }

    /** What happened after the decision, as known at {@code knownAt}. */
    public static DecisionContext.Outcome outcome(UUID obligationId, List<LoanHistoryFold.Event> history,
                                                  int decisionSeq, Instant decidedAt, Instant knownAt) {
        HistoricalLoanState end = LoanHistoryFold.fold(obligationId, history, knownAt, knownAt);
        List<LoanHistoryFold.Event> after = history.stream()
                .filter(e -> e.loanSeq() > decisionSeq && LoanHistoryFold.visible(e, knownAt, knownAt)).toList();
        boolean defaulted = after.stream().anyMatch(e -> e.type() == LendingEventType.DEFAULT_DECLARED);
        boolean settled = after.stream().anyMatch(e -> e.type() == LendingEventType.LOAN_SETTLED);
        return new DecisionContext.Outcome(knownAt, end.status(),
                maxDaysPastDue(obligationId, history, decidedAt, knownAt), defaulted, settled,
                settled && end.interestWaivedMinor() > 0, end.principalRepaidMinor(), end.interestRepaidMinor(),
                after.stream().map(e -> new DecisionContext.TraceEvent(e.loanSeq(), e.type().name(), e.effectiveAt(),
                        e.recordedAt(), e.payload())).toList());
    }

    /**
     * The worst days-past-due reached in (from, to], derived from facts: DPD can only peak
     * just before a repayment or at the end of the window, so those are the instants checked.
     */
    public static long maxDaysPastDue(UUID obligationId, List<LoanHistoryFold.Event> history, Instant from, Instant to) {
        List<Instant> checkpoints = new ArrayList<>();
        for (LoanHistoryFold.Event e : history) {
            if (e.type() == LendingEventType.REPAYMENT_RECEIVED && e.effectiveAt().isAfter(from)
                    && LoanHistoryFold.visible(e, to, to)) {
                checkpoints.add(e.effectiveAt().minusNanos(1000));
            }
        }
        checkpoints.add(to);
        long max = 0;
        for (Instant c : checkpoints) {
            Delinquency.Status s = LoanHistoryFold.fold(obligationId, history, c, c).derivedDelinquency();
            if (s != null) {
                max = Math.max(max, s.daysPastDue());
            }
        }
        return max;
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static LocalDate day(Instant i) {
        return LocalDate.ofInstant(i, ZoneOffset.UTC);
    }
}
