package com.wbank.support;

import com.wbank.account.domain.Account;
import com.wbank.customer.domain.Customer;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.LoanView;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.history.CreditDecisionSnapshotRepository;
import com.wbank.platform.error.BusinessRuleViolationException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A controlled, deterministic lending history, generated through the real services with a
 * controlled clock. Every action happens at a fixed offset from a base date, so two runs
 * with bases exactly one (non-leap) year apart are the same history shifted in time.
 *
 * <pre>
 *   A  on time      3 instalments, each paid on its due date            -> SETTLED
 *   B  early        6 instalments, paid off 36 days after disbursement  -> SETTLED (interest waived)
 *   C  cured        misses 2 instalments (reaches 35 DPD), catches up    -> SETTLED
 *   D  defaulted    never pays; 90 DPD; default declared                 -> DEFAULTED
 *   E  recovered    as D, then repays in full after default              -> SETTLED
 *   F  declined     application declined
 *   G  refused      D's borrower re-applies after defaulting: policy blocks approval; declined
 * </pre>
 *
 * <p>Not a Spring bean: it needs the {@link MutableClock}, which only lending test contexts have.
 */
public class SyntheticLendingHistory {

    private final DomainFixtures d;
    private final LoanService loans;
    private final MutableClock clock;
    private final CreditDecisionSnapshotRepository snapshots;
    private final Map<String, UUID> loanIds = new LinkedHashMap<>();

    public SyntheticLendingHistory(DomainFixtures d, LoanService loans, MutableClock clock,
                                   CreditDecisionSnapshotRepository snapshots) {
        this.d = d;
        this.loans = loans;
        this.clock = clock;
        this.snapshots = snapshots;
    }

    public record Story(String name, UUID obligationId, UUID partyId, UUID approvalDecisionId,
                        String approvalSnapshotSha256, Instant decidedAt) {}

    public record Run(LocalDate base, Map<String, Story> stories, Map<String, Instant> milestones, Instant end) {
        public Story get(String name) {
            return stories.get(name);
        }
    }

    public Run generate(LocalDate base) {
        Map<String, Instant> milestones = new LinkedHashMap<>();
        at(base, 8);
        Map<String, Customer> customers = new LinkedHashMap<>();
        Map<String, Account> accounts = new LinkedHashMap<>();
        for (String s : new String[] {"A", "B", "C", "D", "E", "F"}) {
            Customer c = d.activeCustomer();
            Account a = d.activeAccount(c.getId(), "USD");
            d.fund(a.getId(), 1_000_000, "USD");
            customers.put(s, c);
            accounts.put(s, a);
        }

        at(base, 9);
        Map<String, Obligation> apps = new LinkedHashMap<>();
        long[][] terms = {{300_000, 3}, {600_000, 6}, {300_000, 3}, {300_000, 3}, {300_000, 3}, {5_000_000, 12}};
        String[] names = {"A", "B", "C", "D", "E", "F"};
        for (int i = 0; i < names.length; i++) {
            apps.put(names[i], loans.propose(customers.get(names[i]).getId(), accounts.get(names[i]).getId(),
                    d.money(terms[i][0], "USD"), 1_200, (int) terms[i][1]));
        }

        at(base, 10);
        milestones.put("decisions", clock.instant());
        Map<String, Story> stories = new LinkedHashMap<>();
        for (String s : new String[] {"A", "B", "C", "D", "E"}) {
            loans.approve(apps.get(s).getId(), "within policy; story " + s,
                    Map.of("declaredMonthlyIncome", "3500.00", "employment", "salaried"));
            stories.put(s, story(s, apps.get(s).getId(), LoanDecision.Kind.APPROVED));
        }
        loans.decline(apps.get("F").getId(), "affordability not demonstrated", Map.of("declaredMonthlyIncome", "900.00"));
        stories.put("F", story("F", apps.get("F").getId(), LoanDecision.Kind.DECLINED));

        at(base, 11);
        for (String s : new String[] {"A", "B", "C", "D", "E"}) {
            loans.disburse(apps.get(s).getId());
        }

        // month 1
        at(base.plusMonths(1), 9);
        payDue("A");
        at(base.plusMonths(1).plusDays(1), 9);
        serviceAll(stories, base.plusMonths(1).plusDays(1));
        at(base.plusMonths(1).plusDays(5), 9);
        milestones.put("B-payoff", clock.instant());
        loans.repay(stories.get("B").obligationId(),
                d.money(loans.view(stories.get("B").obligationId()).quote().payoffMinor(), "USD"), "B-payoff-" + base);

        // month 2
        at(base.plusMonths(2), 9);
        payDue("A");
        LocalDate cLate = base.plusMonths(1).plusDays(35);
        at(cLate, 9);
        milestones.put("C-35dpd", clock.instant());
        serviceAll(stories, cLate);
        at(cLate.plusDays(1), 9);
        milestones.put("C-cure", clock.instant());
        payDue("C");

        // month 3
        at(base.plusMonths(3), 9);
        milestones.put("A-final", clock.instant());
        payDue("A");
        payDue("C");

        // 90 days past the first missed due date: default D and E
        LocalDate day90 = base.plusMonths(1).plusDays(90);
        at(day90, 9);
        serviceAll(stories, day90);
        at(day90, 10);
        milestones.put("defaults", clock.instant());
        loans.declareDefault(stories.get("D").obligationId(), "90 DPD, no contact");
        loans.declareDefault(stories.get("E").obligationId(), "90 DPD, no contact");

        // D's borrower re-applies after defaulting: the policy refuses approval; the bank declines.
        at(day90.plusDays(1), 9);
        Obligation g = loans.propose(customers.get("D").getId(), accounts.get("D").getId(), d.money(100_000, "USD"),
                1_200, 3);
        at(day90.plusDays(1), 10);
        milestones.put("G-decision", clock.instant());
        try {
            loans.approve(g.getId(), "re-application", Map.of());
            throw new IllegalStateException("policy should have blocked approval");
        } catch (BusinessRuleViolationException expected) {
            loans.decline(g.getId(), "existing defaulted obligation", Map.of());
        }
        stories.put("G", story("G", g.getId(), LoanDecision.Kind.DECLINED));

        // E recovers in full
        at(base.plusMonths(5), 9);
        milestones.put("E-recovery", clock.instant());
        loans.repay(stories.get("E").obligationId(),
                d.money(loans.view(stories.get("E").obligationId()).quote().payoffMinor(), "USD"), "E-recovery-" + base);

        at(base.plusMonths(6), 9);
        return new Run(base, stories, milestones, clock.instant());
    }

    private Story story(String name, UUID obligationId, LoanDecision.Kind kind) {
        LoanView v = loans.view(obligationId);
        LoanDecision decision = v.decisions().stream().filter(x -> x.getDecision() == kind).findFirst().orElseThrow();
        loanIds.put(name, obligationId);
        String sha = snapshots.findByDecisionId(decision.getId()).orElseThrow().getContentSha256();
        return new Story(name, obligationId, v.obligation().getDebtorPartyId(), decision.getId(), sha,
                decision.getDecidedAt());
    }

    private void payDue(String story) {
        UUID id = loanIds.get(story);
        long due = loans.view(id).quote().dueNowMinor();
        if (due > 0) {
            loans.repay(id, d.money(due, "USD"), story + "-" + clock.instant());
        }
    }

    private void serviceAll(Map<String, Story> stories, LocalDate asOf) {
        for (Story s : stories.values()) {
            loans.service(s.obligationId(), asOf);
        }
    }

    private void at(LocalDate date, int hour) {
        clock.set(date.atTime(hour, 0).toInstant(ZoneOffset.UTC));
    }
}
