package com.wbank.obligation.history;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.AccountService;
import com.wbank.ledger.LedgerService;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.LoanView;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.persistence.ObligationRepository;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.MutableClock;
import com.wbank.support.PostgresIntegrationTest;
import com.wbank.support.SyntheticLendingHistory;
import com.wbank.support.SyntheticLendingHistory.Run;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Point-in-time lending history against PostgreSQL, over a controlled synthetic history
 * (see {@link SyntheticLendingHistory}) generated once with base date 2029-01-04.
 */
@Import(MutableClock.Config.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LendingHistoryIntegrationTest extends PostgresIntegrationTest {

    static final LocalDate BASE = LocalDate.of(2029, 1, 4);

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired LoanService loans;
    @Autowired MutableClock clock;
    @Autowired CreditDecisionSnapshotRepository snapshotRepo;
    @Autowired DecisionSnapshots snapshots;
    @Autowired PointInTimeService pit;
    @Autowired DecisionReconstructionService reconstruct;
    @Autowired LendingDatasetService datasets;
    @Autowired CorrectionService corrections;
    @Autowired CreditPolicyService policies;
    @Autowired ObligationRepository obligations;
    @Autowired AccountService accounts;
    @Autowired LedgerService ledger;
    @Autowired ObjectMapper json;

    Run run;

    @BeforeAll
    void generate() {
        run = new SyntheticLendingHistory(d, loans, clock, snapshotRepo).generate(BASE);
    }

    static Instant at(LocalDate date, int hour) {
        return date.atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    private UUID loan(String story) {
        return run.get(story).obligationId();
    }

    /** Every scalar value in a JSON tree, as text. */
    private static List<String> values(JsonNode n) {
        List<String> out = new java.util.ArrayList<>();
        if (n.isValueNode()) {
            out.add(n.asText());
        } else {
            n.forEach(child -> out.addAll(values(child)));
        }
        return out;
    }

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return causeChain(t);
    }

    // ------------------------------------------------------------------ 1

    @Test
    void historicalLoanStateIsReconstructedAtATimestamp() {
        // A, after instalment 2 was paid (Mar 4) and before instalment 3 (Apr 4).
        HistoricalLoanState a = pit.loanAt(loan("A"), at(LocalDate.of(2029, 3, 10), 12), null);
        assertThat(a.status()).isEqualTo("ACTIVE");
        assertThat(a.repayments()).hasSize(2);
        assertThat(a.schedule().get(0).principalPaidMinor()).isEqualTo(a.schedule().get(0).principalDueMinor());
        assertThat(a.schedule().get(1).principalPaidMinor()).isEqualTo(a.schedule().get(1).principalDueMinor());
        assertThat(a.schedule().get(2).principalPaidMinor()).isZero();
        assertThat(a.outstandingPrincipalMinor()).isEqualTo(a.schedule().get(2).principalDueMinor());
        assertThat(a.derivedDelinquency().bucket()).isEqualTo(Delinquency.Bucket.CURRENT);

        // C at its worst: 35 days past due, observed by servicing that morning.
        HistoricalLoanState c = pit.loanAt(loan("C"), run.milestones().get("C-35dpd"), null);
        assertThat(c.derivedDelinquency().daysPastDue()).isEqualTo(35);
        assertThat(c.lastObservedDelinquency().bucket()).isEqualTo("DPD_30_59");
        assertThat(c.derivedDelinquency().overdueInstallments()).isEqualTo(2);
    }

    @Test
    void recordTimeIsDistinctFromEffectiveTime() {
        // C's first-period interest is EFFECTIVE on the due date (Feb 4) but was only BOOKED by the
        // servicing run on Feb 5. At noon on Feb 4 the bank did not yet know it; with hindsight it is there.
        Instant feb4Noon = at(LocalDate.of(2029, 2, 4), 12);
        HistoricalLoanState asKnownThen = pit.loanAt(loan("C"), feb4Noon, feb4Noon);
        HistoricalLoanState withHindsight = pit.loanAt(loan("C"), feb4Noon, run.end());
        assertThat(asKnownThen.interestAccruedMinor()).isZero();
        assertThat(withHindsight.interestAccruedMinor()).isEqualTo(withHindsight.schedule().get(0).interestDueMinor());
        assertThat(asKnownThen.eventsNotYetVisible()).isGreaterThan(withHindsight.eventsNotYetVisible());
    }

    // ------------------------------------------------------------------ 2

    @Test
    void eventsAfterTheTimestampAreExcluded() {
        Instant beforeDefault = at(LocalDate.of(2029, 3, 1), 12);
        HistoricalLoanState dEarly = pit.loanAt(loan("D"), beforeDefault, null);
        assertThat(dEarly.status()).isEqualTo("ACTIVE");
        assertThat(dEarly.decisions()).extracting(HistoricalLoanState.Decision::type).containsExactly("CREDIT_DECISION");
        assertThat(dEarly.eventsNotYetVisible()).isPositive();
        assertThat(pit.events(loan("D"), beforeDefault)).allSatisfy(e -> assertThat(e.getRecordedAt()).isBeforeOrEqualTo(beforeDefault));

        // Before the application existed, the loan is unknown.
        HistoricalLoanState before = pit.loanAt(loan("D"), at(BASE, 8), null);
        assertThat(before.known()).isFalse();
        assertThat(before.status()).isNull();

        HistoricalLoanState dNow = pit.loanAt(loan("D"), run.end(), null);
        assertThat(dNow.status()).isEqualTo("DEFAULTED");
        assertThat(dNow.eventsNotYetVisible()).isZero();
    }

    // ------------------------------------------------------------------ 3, 5, 12

    @Test
    void aDecisionSnapshotContainsOnlyWhatWasKnownAtDecisionTime() {
        DecisionContext ctx = reconstruct.reconstruct(run.get("D").approvalDecisionId(), null);
        JsonNode snap = ctx.snapshot();
        assertThat(ctx.snapshotVerified()).isTrue();
        assertThat(snap.at("/decision/decidedAt").asText()).isEqualTo(ctx.decidedAt().toString());
        assertThat(snap.at("/subject/customerStatus").asText()).isEqualTo("ACTIVE");
        assertThat(snap.at("/application/principalMinor").asLong()).isEqualTo(300_000);
        assertThat(snap.get("existingObligations")).isEmpty();
        // The account shows the funded balance only: the loan had not even been disbursed.
        JsonNode deposit = snap.get("accounts").get(0);
        assertThat(deposit.get("ledgerBalanceMinor").asLong()).isEqualTo(1_000_000);
        assertThat(snap.at("/resultingAction/toStatus").asText()).isEqualTo("APPROVED");
        // Nothing from D's future (missed payments, delinquency, default) exists in it: no value in the
        // snapshot is a later status or a delinquency bucket, and no delinquency data is present.
        assertThat(values(snap)).doesNotContain("DEFAULTED", "ACTIVE_WITH_ARREARS")
                .noneMatch(v -> v.startsWith("DPD_"));
        assertThat(snap.findValues("daysPastDue")).isEmpty();
        assertThat(snap.has("subjectLoan")).isFalse();

        assertThat(ctx.loanBeforeDecision().status()).isEqualTo("PROPOSED");
        assertThat(ctx.loanBeforeDecision().repayments()).isEmpty();
        assertThat(ctx.resultingStatus()).isEqualTo("APPROVED");
        assertThat(ctx.outcome()).isNull(); // no outcome unless asked for, and then clearly separated
    }

    @Test
    void laterDelinquencyNeverAppearsInAnEarlierDecisionDataset() {
        // Dataset as known at Feb 1: D had been approved but nothing had gone wrong yet.
        Instant feb1 = at(LocalDate.of(2029, 2, 1), 0);
        LendingDatasetService.Row early = row(datasets.creditDecisions(Duration.ofDays(180), feb1), "D");
        assertThat(early.censored()).isTrue();
        assertThat(early.outcome().defaulted()).isFalse();
        assertThat(early.outcome().maxDaysPastDue()).isZero();
        assertThat(early.outcome().eventsAfterDecision()).extracting(DecisionContext.TraceEvent::type)
                .doesNotContain("DELINQUENCY_CHANGED", "DEFAULT_DECLARED");

        // Same decision, 30-day horizon, but the dataset built today: the outcome still stops at day 30.
        LendingDatasetService.Row thirty = row(datasets.creditDecisions(Duration.ofDays(30), run.end()), "D");
        assertThat(thirty.censored()).isFalse();
        assertThat(thirty.outcomeKnownAt()).isEqualTo(run.get("D").decidedAt().plus(Duration.ofDays(30)));
        assertThat(thirty.outcome().defaulted()).isFalse();
        assertThat(thirty.outcome().maxDaysPastDue()).isZero();

        // With a long enough horizon the default is the outcome, and the context is unchanged.
        LendingDatasetService.Row full = row(datasets.creditDecisions(Duration.ofDays(180), run.end()), "D");
        assertThat(full.outcome().defaulted()).isTrue();
        assertThat(full.outcome().maxDaysPastDue()).isGreaterThanOrEqualTo(90);
        assertThat(full.context()).isEqualTo(early.context());
        assertThat(values(full.context())).doesNotContain("DEFAULTED");
    }

    @Test
    void aDecisionIsTraceableFromEvidenceToOutcome() {
        DecisionContext ctx = reconstruct.reconstruct(run.get("E").approvalDecisionId(), run.end());
        // evidence
        assertThat(ctx.snapshot().at("/evidence/suppliedByDecider/declaredMonthlyIncome").asText()).isEqualTo("3500.00");
        assertThat(ctx.snapshot().get("ruleEvaluation")).allSatisfy(r -> assertThat(r.get("passed").asBoolean()).isTrue());
        // decision -> action
        assertThat(ctx.kind()).isEqualTo("APPROVED");
        assertThat(ctx.resultingStatus()).isEqualTo("APPROVED");
        // outcome
        DecisionContext.Outcome o = ctx.outcome();
        assertThat(o.defaulted()).isTrue();
        assertThat(o.settled()).isTrue();
        assertThat(o.statusAsKnown()).isEqualTo("SETTLED");
        assertThat(o.maxDaysPastDue()).isGreaterThanOrEqualTo(90);
        List<String> types = o.eventsAfterDecision().stream().map(DecisionContext.TraceEvent::type).toList();
        assertThat(types).containsSubsequence("LOAN_DISBURSED", "SCHEDULE_ESTABLISHED", "DELINQUENCY_CHANGED",
                "DEFAULT_DECLARED", "REPAYMENT_RECEIVED", "LOAN_SETTLED");
        assertThat(o.eventsAfterDecision()).allSatisfy(e -> assertThat(e.effectiveAt()).isAfter(ctx.decidedAt()));

        // The default decision is itself a traceable decision with its own snapshot.
        UUID defaultDecision = loans.view(loan("E")).decisions().stream()
                .filter(x -> x.getDecision().name().equals("DEFAULT_DECLARED")).findFirst().orElseThrow().getId();
        DecisionContext def = reconstruct.reconstruct(defaultDecision, null);
        assertThat(def.snapshot().at("/subjectLoan/delinquency/daysPastDue").asLong()).isEqualTo(90);
        assertThat(def.snapshot().at("/ruleEvaluation/0/rule").asText()).isEqualTo("DEFAULT_THRESHOLD_MET");
        assertThat(def.loanBeforeDecision().status()).isEqualTo("ACTIVE");
        assertThat(def.resultingStatus()).isEqualTo("DEFAULTED");
    }

    @Test
    void theSnapshotAgreesWithAnIndependentHistoricalReconstruction() {
        // G was decided after D defaulted: the snapshot (captured from live state) and the
        // reconstruction (folded from history) are two independent routes to the same facts.
        DecisionContext g = reconstruct.reconstruct(run.get("G").approvalDecisionId(), null);
        JsonNode existing = g.snapshot().get("existingObligations");
        assertThat(existing).hasSize(1);
        HistoricalLoanState dThen = g.borrowerAtDecision().stream()
                .filter(s -> s.obligationId().equals(loan("D"))).findFirst().orElseThrow();
        assertThat(existing.get(0).get("status").asText()).isEqualTo("DEFAULTED").isEqualTo(dThen.status());
        assertThat(existing.get(0).get("outstandingPrincipalMinor").asLong()).isEqualTo(dThen.outstandingPrincipalMinor());
        assertThat(existing.get(0).get("daysPastDue").asLong()).isEqualTo(dThen.derivedDelinquency().daysPastDue());
        assertThat(g.snapshot().get("ruleEvaluation")).anySatisfy(r -> {
            assertThat(r.get("rule").asText()).isEqualTo("NO_DEFAULTED_OBLIGATIONS");
            assertThat(r.get("passed").asBoolean()).isFalse();
        });
        assertThat(g.kind()).isEqualTo("DECLINED");
    }

    // ------------------------------------------------------------------ 4

    @Test
    void laterRepaymentsCannotAlterAHistoricalSnapshot() {
        CreditDecisionSnapshot s = snapshotRepo.findByDecisionId(run.get("A").approvalDecisionId()).orElseThrow();
        assertThat(s.getContentSha256()).isEqualTo(run.get("A").approvalSnapshotSha256());
        assertThat(snapshots.verify(s)).isTrue();
        // A has since been fully repaid, but its approval snapshot still shows the world at approval.
        assertThat(obligations.findById(loan("A")).orElseThrow().getStatus().name()).isEqualTo("SETTLED");
        DecisionContext ctx = reconstruct.reconstruct(run.get("A").approvalDecisionId(), run.end());
        assertThat(ctx.snapshot().at("/accounts/0/ledgerBalanceMinor").asLong()).isEqualTo(1_000_000);
        assertThat(ctx.loanBeforeDecision().repayments()).isEmpty();
        assertThat(ctx.outcome().settled()).isTrue(); // the outcome lives only in the outcome section
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE credit_decision_snapshot SET content = '{}'::jsonb WHERE id = ?", s.getId())))
                .contains("append-only");
    }

    // ------------------------------------------------------------------ 6

    @Test
    void policyVersionsAreRecordedAndPreserved() {
        assertThat(run.stories().values()).allSatisfy(story -> {
            DecisionContext ctx = reconstruct.reconstruct(story.approvalDecisionId(), null);
            assertThat(ctx.policyCode()).isEqualTo(CreditPolicy.LENDING);
            assertThat(ctx.policyVersion()).isEqualTo(1);
            assertThat(ctx.policyRules().get("maxPrincipalMajor").asLong()).isEqualTo(50_000);
        });

        // Publish v2 (stricter principal limit) effective 2031-06-01; decide before and after.
        clock.set(at(LocalDate.of(2031, 5, 30), 9));
        CreditPolicyRules v1 = policies.rules(policies.inForceAt(clock.instant()));
        assertThatThrownBy(() -> policies.publish(v1, at(LocalDate.of(2031, 5, 1), 0), "retroactive"))
                .isInstanceOf(BusinessRuleViolationException.class);
        CreditPolicy v2 = policies.versions().stream().filter(p -> p.getVersion() == 2).findFirst()
                .orElseGet(() -> policies.publish(new CreditPolicyRules(true, 1_000, v1.maxInstallments(),
                        v1.maxAnnualRateBps(), true, v1.maxExistingDaysPastDue(),
                        v1.defaultDeclarationMinDaysPastDue()), at(LocalDate.of(2031, 6, 1), 0),
                        "principal limit lowered to 1,000.00"));
        assertThat(v2.getVersion()).isEqualTo(2);

        var borrower = d.activeCustomer();
        var account = d.activeAccount(borrower.getId(), "USD");
        Obligation beforeV2 = loans.propose(borrower.getId(), account.getId(), d.money(500_000, "USD"), 1_200, 6);
        loans.approve(beforeV2.getId(), "under v1");
        clock.set(at(LocalDate.of(2031, 6, 2), 9));
        Obligation afterV2 = loans.propose(borrower.getId(), account.getId(), d.money(500_000, "USD"), 1_200, 6);
        assertThatThrownBy(() -> loans.approve(afterV2.getId(), "under v2"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("v2")
                .hasMessageContaining("PRINCIPAL_WITHIN_LIMIT");
        Obligation small = loans.propose(borrower.getId(), account.getId(), d.money(50_000, "USD"), 1_200, 6);
        loans.approve(small.getId(), "under v2");

        assertThat(loans.view(beforeV2.getId()).decisions().get(0).getPolicyVersion()).isEqualTo(1);
        assertThat(loans.view(small.getId()).decisions().get(0).getPolicyVersion()).isEqualTo(2);
        // Old decisions still reconstruct with the rules they were made under.
        DecisionContext old = reconstruct.reconstruct(run.get("A").approvalDecisionId(), null);
        assertThat(old.policyVersion()).isEqualTo(1);
        assertThat(old.snapshot().at("/policy/rules/maxPrincipalMajor").asLong()).isEqualTo(50_000);
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE credit_policy SET rules = '{}'::jsonb WHERE version = 1"))).contains("append-only");
    }

    // ------------------------------------------------------------------ 7

    @Test
    void historyIsAppendOnlyAndCannotBeForged() {
        UUID eventId = pit.events(loan("A"), null).get(0).getId();
        assertThat(rejection(() -> f.jdbc.update("UPDATE lending_event SET payload = '{}'::jsonb WHERE id = ?", eventId)))
                .contains("append-only");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM lending_event WHERE id = ?", eventId)))
                .contains("append-only");
        int next = pit.events(loan("A"), null).size() + 1;
        String insert = """
                INSERT INTO lending_event (id, obligation_id, loan_seq, event_type, event_kind, effective_at, recorded_at,
                    payload, actor, actor_type, origin) VALUES (?, ?, ?, 'LOAN_SETTLED', 'FACT', ?, ?, '{}', 'psql', 'HUMAN', 'LIVE')""";
        Instant later = run.end().plusSeconds(60);
        // future-dated
        assertThat(rejection(() -> f.jdbc.update(insert, UUID.randomUUID(), loan("A"), next,
                java.sql.Timestamp.from(later.plusSeconds(60)), java.sql.Timestamp.from(later))))
                .contains("lending_event_not_future_dated");
        // gap in the sequence
        assertThat(rejection(() -> f.jdbc.update(insert, UUID.randomUUID(), loan("A"), next + 1,
                java.sql.Timestamp.from(later), java.sql.Timestamp.from(later)))).contains("skips loan_seq");
        // recorded before its predecessor
        assertThat(rejection(() -> f.jdbc.update(insert, UUID.randomUUID(), loan("A"), next,
                java.sql.Timestamp.from(at(BASE, 0)), java.sql.Timestamp.from(at(BASE, 0))))).contains("before its predecessor");
        // a lending fact written without its history event
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update("""
                INSERT INTO delinquency_event (id, obligation_id, as_of, from_bucket, to_bucket, days_past_due,
                    overdue_principal_minor, overdue_interest_minor, recorded_at)
                VALUES (?, ?, DATE '2029-06-01', 'DPD_90_PLUS', 'CURRENT', 0, 0, 0, now())""", UUID.randomUUID(), loan("D")))))
                .contains("without its lending_event");
        // a status that the history does not justify
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update(
                "UPDATE obligation SET status = 'SETTLED', settled_at = now() WHERE id = ?", loan("D")))))
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ 8

    @Test
    void correctionsAreNewEventsAndNeverRewriteHistory() {
        LendingEvent decision = pit.events(loan("C"), null).stream()
                .filter(e -> e.getEventType() == LendingEventType.CREDIT_DECISION).findFirst().orElseThrow();
        String original = decision.getPayload();
        clock.set(run.end().plus(Duration.ofDays(3)));
        LendingEvent correction = corrections.correct(decision.getId(), "evidence.declaredMonthlyIncome", "2900.00",
                "payslip showed 2,900.00; 3,500.00 was mis-keyed");
        assertThat(correction.getEventType()).isEqualTo(LendingEventType.EVENT_CORRECTED);
        assertThat(correction.getEffectiveAt()).isEqualTo(decision.getEffectiveAt());
        assertThat(correction.getRecordedAt()).isAfter(decision.getRecordedAt());
        assertThat(f.jdbc.queryForObject("SELECT payload::text FROM lending_event WHERE id = ?", String.class,
                decision.getId())).isEqualTo(f.jdbc.queryForObject("SELECT ?::jsonb::text", String.class, original));

        // As the bank knew it at decision time: no correction. As known now: the correction, kept separate.
        UUID decisionId = run.get("C").approvalDecisionId();
        assertThat(reconstruct.reconstruct(decisionId, null).laterCorrections()).isEmpty();
        DecisionContext now = reconstruct.reconstruct(decisionId, clock.instant());
        assertThat(now.laterCorrections()).singleElement().satisfies(c -> {
            assertThat(c.previousValue().asText()).isEqualTo("3500.00");
            assertThat(c.correctedValue().asText()).isEqualTo("2900.00");
        });
        assertThat(now.snapshot().at("/evidence/suppliedByDecider/declaredMonthlyIncome").asText()).isEqualTo("3500.00");
        Instant decided = run.get("C").decidedAt();
        assertThat(pit.loanAt(loan("C"), decided, decided).corrections()).isEmpty();
        assertThat(pit.loanAt(loan("C"), decided, clock.instant()).corrections()).hasSize(1);

        LendingEvent repayment = pit.events(loan("C"), null).stream()
                .filter(e -> e.getEventType() == LendingEventType.REPAYMENT_RECEIVED).findFirst().orElseThrow();
        assertThatThrownBy(() -> corrections.correct(repayment.getId(), "rationale", "x", "no"))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("financial fact");
    }

    // ------------------------------------------------------------------ 9

    @Test
    void eventsRecordedTogetherHaveADeterministicOrder() {
        List<LendingEvent> a = pit.events(loan("A"), null);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i).getLoanSeq()).isEqualTo(i + 1);
            if (i > 0) {
                assertThat(a.get(i).getRecordedAt()).isAfterOrEqualTo(a.get(i - 1).getRecordedAt());
                assertThat(a.get(i).getGlobalSeq()).isGreaterThan(a.get(i - 1).getGlobalSeq());
            }
        }
        // A's final payment: accrual, repayment and settlement were recorded at the same instant.
        Instant finalPayment = run.milestones().get("A-final");
        List<LendingEventType> sameInstant = a.stream().filter(e -> e.getRecordedAt().equals(finalPayment))
                .map(LendingEvent::getEventType).toList();
        assertThat(sameInstant).containsExactly(LendingEventType.INTEREST_ACCRUED,
                LendingEventType.REPAYMENT_RECEIVED, LendingEventType.LOAN_SETTLED);
        assertThat(pit.loanAt(loan("A"), finalPayment, null).status()).isEqualTo("SETTLED");
        assertThat(pit.loanAt(loan("A"), finalPayment.minusNanos(1000), null).status()).isEqualTo("ACTIVE");
        assertThat(pit.events(loan("A"), null)).extracting(LendingEvent::getId)
                .containsExactlyElementsOf(a.stream().map(LendingEvent::getId).toList());
    }

    // ------------------------------------------------------------------ 10

    @Test
    void theDecisionDatasetIsReproducible() {
        Duration horizon = Duration.ofDays(180);
        Instant knownAt = run.end();
        String first = datasets.canonical(datasets.creditDecisions(horizon, knownAt), horizon, knownAt);
        String second = datasets.canonical(datasets.creditDecisions(horizon, knownAt), horizon, knownAt);
        assertThat(CanonicalJson.sha256(second)).isEqualTo(CanonicalJson.sha256(first));

        // A second, independent generation one (non-leap) year later tells the same story.
        Run again = new SyntheticLendingHistory(d, loans, clock, snapshotRepo).generate(BASE.plusYears(1));
        Map<String, String> r1 = summary(datasets.creditDecisions(horizon, knownAt), run);
        Map<String, String> r2 = summary(datasets.creditDecisions(horizon, again.end()), again);
        assertThat(r2).isEqualTo(r1);
        // Days past due keep growing after default: at the 180-day horizon D is 149 days past due.
        assertThat(r1).containsEntry("D", "APPROVED v1 -> DEFAULTED maxDpd=149 defaulted=true settled=false early=false");
        assertThat(r1).containsEntry("B", "APPROVED v1 -> SETTLED maxDpd=5 defaulted=false settled=true early=true");
        assertThat(r1).containsEntry("F", "DECLINED v1 -> no outcome (not granted)");
    }

    private Map<String, String> summary(List<LendingDatasetService.Row> rows, Run r) {
        Map<String, String> byNumber = rows.stream().filter(x -> x.obligationNumber() != null)
                .collect(Collectors.toMap(LendingDatasetService.Row::obligationNumber, x -> {
                    var o = x.outcome();
                    return x.decision() + " v" + x.policyVersion() + " -> " + (o == null ? "no outcome (not granted)"
                            : o.statusAsKnown() + " maxDpd=" + o.maxDaysPastDue() + " defaulted=" + o.defaulted()
                                    + " settled=" + o.settled() + " early=" + o.settledEarly());
                }, (a, b) -> a));
        return r.stories().entrySet().stream().filter(e -> !e.getKey().equals("G"))
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> byNumber.get(obligations.findById(e.getValue().obligationId()).orElseThrow()
                                .getObligationNumber())));
    }

    private LendingDatasetService.Row row(List<LendingDatasetService.Row> rows, String story) {
        String number = obligations.findById(loan(story)).orElseThrow().getObligationNumber();
        return rows.stream().filter(x -> number.equals(x.obligationNumber())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ 11

    @Test
    void currentStateAndReconstructedStateAgree() {
        Instant now = clock.instant().isAfter(run.end()) ? clock.instant() : run.end();
        for (var story : run.stories().values()) {
            UUID id = story.obligationId();
            HistoricalLoanState h = pit.loanAt(id, now, now);
            LoanView v = loans.view(id);
            assertThat(h.status()).as(story.name()).isEqualTo(v.obligation().getStatus().name());
            assertThat(h.decisions()).hasSameSizeAs(v.decisions());
            assertThat(h.repayments()).hasSameSizeAs(v.repayments());
            if (v.ledgerOutstandingPrincipalMinor() != null) {
                assertThat(h.outstandingPrincipalMinor()).as(story.name()).isEqualTo(v.ledgerOutstandingPrincipalMinor());
            }
            if (v.ledgerInterestReceivableMinor() != null) {
                assertThat(h.accruedUnpaidInterestMinor()).as(story.name()).isEqualTo(v.ledgerInterestReceivableMinor());
            }
            assertThat(h.interestAccruedMinor()).isEqualTo(v.accruedInterestMinor());
            assertThat(h.schedule()).hasSameSizeAs(v.schedule());
            for (int i = 0; i < h.schedule().size(); i++) {
                var hs = h.schedule().get(i);
                var vs = v.schedule().get(i);
                assertThat(List.of(hs.dueDate(), hs.principalDueMinor(), hs.interestDueMinor(), hs.principalPaidMinor(),
                        hs.interestPaidMinor(), hs.interestWaivedMinor())).as(story.name() + " #" + (i + 1))
                        .isEqualTo(List.of(vs.dueDate(), vs.principalDueMinor(), vs.interestDueMinor(),
                                vs.principalPaidMinor(), vs.interestPaidMinor(), vs.interestWaivedMinor()));
            }
        }
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    void theReconstructionReadsOnlyTheHistory() {
        // Structural guard against leakage: the point-in-time service depends on the event store,
        // plus the immutable debtor link used only to find a borrower's loans.
        Set<Class<?>> deps = Set.of(PointInTimeService.class.getDeclaredConstructors()[0].getParameterTypes());
        assertThat(deps).containsExactlyInAnyOrder(LendingEventRepository.class, ObligationRepository.class,
                ObjectMapper.class);
        // The fold is stateless (compiler-generated helpers such as switch tables excepted).
        assertThat(java.util.Arrays.stream(LoanHistoryFold.class.getDeclaredFields())
                .filter(fl -> !fl.isSynthetic() && !fl.getName().startsWith("$"))).isEmpty();
    }
}
