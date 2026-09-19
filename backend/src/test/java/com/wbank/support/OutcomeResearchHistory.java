package com.wbank.support;

import com.wbank.information.FinancialFact.Frequency;
import com.wbank.information.FinancialFact.Kind;
import com.wbank.information.FinancialFact.Provenance;
import com.wbank.information.FinancialInformationService;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.history.CorrectionService;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.PointInTimeService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The seven-story synthetic history extended for outcome research, without changing its
 * stories (decisions at T = base 10:00):
 *
 * <pre>
 *   story  decision   information at T              observed afterwards
 *   A      APPROVED   COMPLETE (verified)           repaid on schedule, settled ~T+92d
 *   B      APPROVED   INSUFFICIENT                  settled early ~T+36d
 *   C      APPROVED   PARTIAL (declared; verified   35 DPD, cured, settled ~T+92d
 *                     only after T)
 *   D      APPROVED   INSUFFICIENT                  default declared ~T+121d; its rationale is
 *                                                   CORRECTED later (appended, not rewritten)
 *   E      APPROVED   INSUFFICIENT                  default ~T+121d, then repaid in full ~T+153d
 *   F, G   DECLINED   -                             nothing: no loan, no outcome
 *   H      APPROVED   INSUFFICIENT                  (decided base+6m+1d) no repayment; servicing ran
 *                                                   late, so the bank's 1+ DPD observation (effective
 *                                                   ~H+40d) was only RECORDED ~H+50d
 * </pre>
 *
 * Not a Spring bean: it drives the {@link MutableClock}.
 */
public final class OutcomeResearchHistory {

    /**
     * @param kThen after D's default, before its correction was recorded
     * @param kH1   after H's first 45 days, before H's late observation was recorded
     * @param kNow  the clock when the history ends
     */
    public record Built(SyntheticLendingHistory.Run run, Map<String, UUID> decisions, Instant hDecided, Instant kThen,
                        Instant kH1, Instant kNow) {}

    private OutcomeResearchHistory() {}

    public static Built build(LocalDate base, DomainFixtures d, LoanService loans, MutableClock clock,
                              CreditDecisionSnapshotRepository snapshots, FinancialInformationService information,
                              CorrectionService corrections, PointInTimeService pit) {
        SyntheticLendingHistory.Run run = new SyntheticLendingHistory(d, loans, clock, snapshots).generate(base,
                new SyntheticLendingHistory.Information() {
                    @Override
                    public void beforeDecisions(Map<String, UUID> p, Instant at) {
                        income(information, p.get("A"), Provenance.VERIFIED, "payslip-A", null, null);
                        information.record(p.get("A"), new FinancialInformationService.Observation(
                                Kind.OBLIGATION_DISCLOSURE, "ALL_RECURRING_OBLIGATIONS", null, null, null, null, null, null,
                                null, null, Provenance.VERIFIED, "bureau", "bureau-A", null, null));
                        income(information, p.get("C"), Provenance.DECLARED, null, null, null);
                    }

                    @Override
                    public void afterDecisions(Map<String, UUID> p, Instant at) {
                        var declared = information.all(p.get("C")).get(0); // verified later: true since declared
                        income(information, p.get("C"), Provenance.VERIFIED, "payslip-C", declared.id(),
                                declared.effectiveAt());
                    }
                });
        Map<String, UUID> decisions = new LinkedHashMap<>();
        run.stories().forEach((k, s) -> decisions.put(k, s.approvalDecisionId()));

        // D's default rationale corrected after the fact (appended; the original stays).
        Instant kThen = run.end().plus(Duration.ofDays(1));
        clock.set(run.end().plus(Duration.ofDays(2)));
        UUID defaultEvent = pit.history(run.get("D").obligationId()).stream()
                .filter(e -> e.type() == LendingEventType.DEFAULT_DECLARED).findFirst().orElseThrow().id();
        corrections.correct(defaultEvent, "rationale", "90 DPD; borrower unreachable at two addresses", "clerical");

        // H: approved after the seven stories; servicing runs late.
        LocalDate hDay = base.plusMonths(6).plusDays(1);
        clock.set(hDay.atTime(9, 0).toInstant(ZoneOffset.UTC));
        var customer = d.activeCustomer();
        var account = d.activeAccount(customer.getId(), "USD");
        d.fund(account.getId(), 1_000_000, "USD");
        Obligation h = loans.propose(customer.getId(), account.getId(), d.money(300_000, "USD"), 1_200, 3);
        clock.set(hDay.atTime(10, 0).toInstant(ZoneOffset.UTC));
        loans.approve(h.getId(), "within policy; story H");
        Instant hDecided = clock.instant();
        clock.set(hDay.atTime(11, 0).toInstant(ZoneOffset.UTC));
        loans.disburse(h.getId());
        LocalDate firstDue = loans.view(h.getId()).schedule().get(0).dueDate();
        clock.set(firstDue.plusDays(20).atTime(9, 0).toInstant(ZoneOffset.UTC));
        loans.service(h.getId(), firstDue.plusDays(10)); // observation effective firstDue+10, recorded firstDue+20
        decisions.put("H", loans.view(h.getId()).decisions().stream()
                .filter(x -> x.getDecision().name().equals("APPROVED")).findFirst().orElseThrow().getId());

        clock.set(firstDue.plusDays(30).atTime(12, 0).toInstant(ZoneOffset.UTC));
        return new Built(run, decisions, hDecided, kThen, hDecided.plus(Duration.ofDays(46)), clock.instant());
    }

    private static void income(FinancialInformationService information, UUID party, Provenance p, String evidence,
                               UUID verifies, Instant effective) {
        information.record(party, new FinancialInformationService.Observation(Kind.INCOME, "SALARY", null, null, 500_000L,
                "USD", Frequency.MONTHLY, null, null, null, p, "application form", evidence, verifies, effective));
    }
}
