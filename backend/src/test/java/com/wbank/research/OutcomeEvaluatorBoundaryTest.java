package com.wbank.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LoanHistoryFold;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Exact temporal boundaries of {@link OutcomeEvaluator}, at the database's precision
 * (microseconds). Both boundaries are inclusive: an event counts iff
 * {@code decidedAt < effective_at <= decidedAt + H} and {@code recorded_at <= knownAt}; a window
 * is complete iff {@code decidedAt + H <= knownAt}.
 */
class OutcomeEvaluatorBoundaryTest {

    static final Instant T = Instant.parse("2025-01-10T10:00:00Z");
    static final int H = 90;
    static final Instant CUTOFF = T.plus(Duration.ofDays(H));
    static final Duration MICRO = Duration.ofNanos(1_000);
    static final OutcomeEvaluator.Definition DEFAULT_90 =
            new OutcomeEvaluator.Definition("DEFAULT_90D", 1, OutcomeEvaluator.Event.DEFAULT, null, H);

    static final UUID DEFAULT_ID = UUID.randomUUID();

    private static LoanHistoryFold.Event event(int seq, LendingEventType type, Instant effective, Instant recorded,
                                               UUID id, UUID corrects) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        if (type == LendingEventType.CREDIT_DECISION || type == LendingEventType.DEFAULT_DECLARED) {
            p.put("decision", type == LendingEventType.DEFAULT_DECLARED ? "DEFAULTED" : "APPROVED");
        }
        if (type == LendingEventType.EVENT_CORRECTED) {
            p.put("field", "rationale");
            p.put("correctedValue", "corrected");
        }
        return new LoanHistoryFold.Event(id, seq, seq, type, effective, recorded, p, corrects, "t");
    }

    /** Decision at T (seq 2), disbursed an hour later, a default at {@code defaultEffective}. */
    private static OutcomeEvaluator.Subject loan(Instant defaultEffective, Instant defaultRecorded,
                                                 LoanHistoryFold.Event... more) {
        List<LoanHistoryFold.Event> h = new ArrayList<>(List.of(
                event(1, LendingEventType.APPLICATION_RECEIVED, T.minusSeconds(3600), T.minusSeconds(3600), UUID.randomUUID(), null),
                event(2, LendingEventType.CREDIT_DECISION, T, T, UUID.randomUUID(), null),
                event(3, LendingEventType.LOAN_DISBURSED, T.plusSeconds(3600), T.plusSeconds(3600), UUID.randomUUID(), null)));
        if (defaultEffective != null) {
            h.add(event(4, LendingEventType.DEFAULT_DECLARED, defaultEffective, defaultRecorded, DEFAULT_ID, null));
        }
        h.addAll(List.of(more));
        return new OutcomeEvaluator.Subject(UUID.randomUUID(), UUID.randomUUID(), "APPROVED", T, 2, h);
    }

    private static String eval(OutcomeEvaluator e, OutcomeEvaluator.Subject s, Instant knownAt) {
        OutcomeEvaluator.Result r = e.evaluate(DEFAULT_90, s, knownAt);
        return r.status() + (r.value() == null ? "" : ":" + r.value());
    }

    /** Every boundary rule; empty iff {@code e} implements them. */
    static List<String> violations(OutcomeEvaluator e) {
        List<String> v = new ArrayList<>();
        Instant late = CUTOFF.plus(Duration.ofDays(1));
        check(v, "event exactly at the outcome cutoff counts", eval(e, loan(CUTOFF, CUTOFF), late), "OBSERVED:OCCURRED");
        check(v, "event 1us before the outcome cutoff counts", eval(e, loan(CUTOFF.minus(MICRO), CUTOFF), late),
                "OBSERVED:OCCURRED");
        check(v, "event 1us after the outcome cutoff does not count",
                eval(e, loan(CUTOFF.plus(MICRO), CUTOFF.plus(MICRO)), late), "OBSERVED:NOT_OCCURRED");
        Instant recorded = CUTOFF.plus(Duration.ofHours(2));
        check(v, "event recorded exactly at the knowledge cutoff is known",
                eval(e, loan(CUTOFF.minus(Duration.ofDays(1)), recorded), recorded), "OBSERVED:OCCURRED");
        check(v, "event recorded 1us after the knowledge cutoff is unknown",
                eval(e, loan(CUTOFF.minus(Duration.ofDays(1)), recorded.plus(MICRO)), recorded), "OBSERVED:NOT_OCCURRED");
        check(v, "event effective before the decision (recorded after) is not an outcome of it",
                eval(e, loan(T.minus(MICRO), T.plus(Duration.ofDays(10))), late), "OBSERVED:NOT_OCCURRED");
        check(v, "event effective exactly at the decision is not after it",
                eval(e, loan(T, T.plus(Duration.ofDays(10))), late), "OBSERVED:NOT_OCCURRED");
        check(v, "knowledge exactly at the outcome cutoff completes the window", eval(e, loan(null, null), CUTOFF),
                "OBSERVED:NOT_OCCURRED");
        check(v, "knowledge 1us before the outcome cutoff leaves it censored", eval(e, loan(null, null),
                CUTOFF.minus(MICRO)), "CENSORED");
        Instant correctionRecorded = CUTOFF.plus(Duration.ofDays(5));
        OutcomeEvaluator.Subject corrected = loan(T.plus(Duration.ofDays(30)), T.plus(Duration.ofDays(30)),
                event(5, LendingEventType.EVENT_CORRECTED, correctionRecorded, correctionRecorded, UUID.randomUUID(),
                        DEFAULT_ID));
        int before = e.evaluate(DEFAULT_90, corrected, correctionRecorded.minus(MICRO)).corrections().size();
        int at = e.evaluate(DEFAULT_90, corrected, correctionRecorded).corrections().size();
        check(v, "a correction is unknown 1us before it was recorded", String.valueOf(before), "0");
        check(v, "a correction is known from the instant it was recorded", String.valueOf(at), "1");
        return v;
    }

    private static void check(List<String> v, String rule, String actual, String expected) {
        if (!actual.equals(expected)) {
            v.add(rule + ": got " + actual + ", expected " + expected);
        }
    }

    @Test
    void boundariesAreInclusiveAtMicrosecondPrecision() {
        assertThat(violations(new OutcomeEvaluator())).isEmpty();
    }

    @Test
    void mutationExclusiveOutcomeCutoffIsCaught() {
        OutcomeEvaluator exclusive = new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, cutoff, knownAt) && e.effectiveAt().isBefore(cutoff);
            }
        };
        assertThat(violations(exclusive)).containsExactly(
                "event exactly at the outcome cutoff counts: got OBSERVED:NOT_OCCURRED, expected OBSERVED:OCCURRED");
    }

    @Test
    void mutationExclusiveKnowledgeCutoffIsCaught() {
        OutcomeEvaluator exclusive = new OutcomeEvaluator() {
            @Override
            protected boolean counts(LoanHistoryFold.Event e, Subject s, Instant cutoff, Instant knownAt) {
                return super.counts(e, s, cutoff, knownAt) && e.recordedAt().isBefore(knownAt);
            }

            @Override
            protected boolean windowComplete(Instant cutoff, Instant knownAt) {
                return cutoff.isBefore(knownAt);
            }
        };
        assertThat(violations(exclusive)).contains(
                "event recorded exactly at the knowledge cutoff is known: got OBSERVED:NOT_OCCURRED, expected OBSERVED:OCCURRED",
                "knowledge exactly at the outcome cutoff completes the window: got CENSORED, expected OBSERVED:NOT_OCCURRED");
    }
}
