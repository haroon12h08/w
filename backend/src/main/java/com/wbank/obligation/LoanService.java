package com.wbank.obligation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.account.domain.AccountStatus;
import com.wbank.account.funds.FundsService;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountStatus;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.obligation.domain.AllocationPolicy;
import com.wbank.obligation.domain.AnnuitySchedule;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.DelinquencyEvent;
import com.wbank.obligation.domain.Installment;
import com.wbank.obligation.domain.InstallmentWaiver;
import com.wbank.obligation.domain.InterestAccrual;
import com.wbank.obligation.domain.LoanDecision;
import com.wbank.obligation.domain.LoanTerms;
import com.wbank.obligation.domain.Obligation;
import com.wbank.obligation.domain.ObligationStatus;
import com.wbank.obligation.domain.ObligationType;
import com.wbank.obligation.domain.Repayment;
import com.wbank.obligation.domain.RepaymentAllocation;
import com.wbank.obligation.persistence.DelinquencyEventRepository;
import com.wbank.obligation.persistence.InstallmentRepository;
import com.wbank.obligation.persistence.InstallmentWaiverRepository;
import com.wbank.obligation.persistence.InterestAccrualRepository;
import com.wbank.obligation.persistence.LoanDecisionRepository;
import com.wbank.obligation.persistence.LoanTermsRepository;
import com.wbank.obligation.persistence.ObligationRepository;
import com.wbank.obligation.persistence.RepaymentAllocationRepository;
import com.wbank.obligation.persistence.RepaymentRepository;
import com.wbank.customer.CustomerService;
import com.wbank.customer.domain.Customer;
import com.wbank.obligation.history.CreditPolicy;
import com.wbank.obligation.history.CreditPolicyRules;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.obligation.history.PolicyEngine;
import com.wbank.obligation.history.CreditPolicyService;
import com.wbank.obligation.history.CreditDecisionSnapshot;
import com.wbank.obligation.history.DecisionSnapshots;
import com.wbank.obligation.history.LendingEventType;
import com.wbank.obligation.history.LendingHistory;
import com.wbank.party.PartyService;
import com.wbank.party.domain.Party;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import com.wbank.platform.persistence.SequenceNumbers;
import com.wbank.product.ProductService;
import com.wbank.product.domain.ProductFamily;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lending lifecycle: application, credit decision, origination and disbursement,
 * interest accrual, repayment and allocation, delinquency, default, settlement.
 *
 * <p>Each public method is ONE database transaction spanning the loan's domain records,
 * the accounts involved, the ledger journals and the audit trail. The obligation row lock
 * serialises every operation on one loan. PostgreSQL re-checks at COMMIT that the loan's
 * principal and accrued-interest positions in the ledger equal what its contract and
 * repayment history say (see {@code wbank_check_obligation}).
 */
@Service
public class LoanService {

    public static final String LOAN_PRODUCT = "TERM_LOAN";

    private final ObligationRepository obligations;
    private final LoanTermsRepository terms;
    private final InstallmentRepository installments;
    private final RepaymentRepository repayments;
    private final RepaymentAllocationRepository allocations;
    private final InterestAccrualRepository accruals;
    private final InstallmentWaiverRepository waivers;
    private final LoanDecisionRepository decisions;
    private final DelinquencyEventRepository delinquencyEvents;
    private final AccountService accounts;
    private final FundsService funds;
    private final ProductService products;
    private final LedgerService ledger;
    private final CurrencyRegistry currencies;
    private final SequenceNumbers sequenceNumbers;
    private final AuditTrail auditTrail;
    private final ObjectMapper json;
    private final Clock clock;
    private final LendingHistory history;
    private final CreditPolicyService policies;
    private final DecisionSnapshots snapshots;
    private final PartyService parties;
    private final CustomerService customers;

    public LoanService(ObligationRepository obligations, LoanTermsRepository terms, InstallmentRepository installments,
                       RepaymentRepository repayments, RepaymentAllocationRepository allocations,
                       InterestAccrualRepository accruals, InstallmentWaiverRepository waivers,
                       LoanDecisionRepository decisions, DelinquencyEventRepository delinquencyEvents,
                       AccountService accounts, FundsService funds, ProductService products, LedgerService ledger,
                       CurrencyRegistry currencies, SequenceNumbers sequenceNumbers, AuditTrail auditTrail,
                       ObjectMapper json, Clock clock, LendingHistory history, CreditPolicyService policies,
                       DecisionSnapshots snapshots, PartyService parties, CustomerService customers) {
        this.history = history;
        this.policies = policies;
        this.snapshots = snapshots;
        this.parties = parties;
        this.customers = customers;
        this.obligations = obligations;
        this.terms = terms;
        this.installments = installments;
        this.repayments = repayments;
        this.allocations = allocations;
        this.accruals = accruals;
        this.waivers = waivers;
        this.decisions = decisions;
        this.delinquencyEvents = delinquencyEvents;
        this.accounts = accounts;
        this.funds = funds;
        this.products = products;
        this.ledger = ledger;
        this.currencies = currencies;
        this.sequenceNumbers = sequenceNumbers;
        this.auditTrail = auditTrail;
        this.json = json;
        this.clock = clock;
    }

    public record RepaymentResult(Repayment repayment, List<RepaymentAllocation> allocations, boolean replayed,
                                  boolean settledLoan) {}

    public record ServicingResult(UUID obligationId, int accrualsPosted, Delinquency.Status delinquency,
                                  boolean bucketChanged) {}

    // ================================================================ application

    /** Records a loan application with its requested terms. No money moves; no schedule exists. */
    @Transactional
    public Obligation propose(UUID customerId, UUID settlementAccountId, Money principal, int annualRateBps,
                              int installmentCount) {
        OperationContext context = RequestContext.forOperation("loan.propose");
        Account settlement = accounts.require(settlementAccountId);
        if (!settlement.getCustomerId().equals(customerId)) {
            throw new BusinessRuleViolationException("loan.settlement_not_owned",
                    "Settlement account %s does not belong to customer %s"
                            .formatted(settlement.getAccountNumber(), customerId));
        }
        if (settlement.getProductFamily() != ProductFamily.DEPOSIT || settlement.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessRuleViolationException("loan.settlement_not_usable",
                    "Settlement account %s must be an ACTIVE deposit account".formatted(settlement.getAccountNumber()));
        }
        if (!settlement.getCurrencyCode().equals(principal.currency().code())) {
            throw new CurrencyMismatchException("Loan currency %s differs from settlement account currency %s"
                    .formatted(principal.currency().code(), settlement.getCurrencyCode()));
        }
        // Validates rate, term and feasibility (principal large enough for the schedule).
        AnnuitySchedule.generate(principal.minorUnits(), annualRateBps, installmentCount, LocalDate.now(clock));

        Account position = accounts.openPosition(customerId, products.require(LOAN_PRODUCT), principal.currency(),
                Money.zero(principal.currency()));
        Obligation obligation = Obligation.propose(UUID.randomUUID(), sequenceNumbers.nextObligationNumber(),
                ObligationType.LOAN, PartyService.INSTITUTION_PARTY_ID, settlement.getOwnerPartyId(), principal,
                position.getId(), settlement.getId(), clock.instant());
        obligations.save(obligation);
        LoanTerms loanTerms = terms.save(LoanTerms.of(obligation.getId(), annualRateBps, installmentCount));

        Map<String, Object> payload = termsEvidence(obligation, loanTerms);
        payload.put("positionAccountId", position.getId().toString());
        payload.put("settlementAccountId", settlement.getId().toString());
        auditTrail.record("loan.proposed", "obligation", obligation.getId(), context, payload);
        Map<String, Object> application = new LinkedHashMap<>(payload);
        application.put("productCode", position.getProductCode());
        history.record(obligation.getId(), LendingEventType.APPLICATION_RECEIVED, obligation.getProposedAt(),
                application, "obligation", obligation.getId(), context);
        return obligation;
    }

    // ============================================================ credit decisions

    @Transactional
    public Obligation approve(UUID obligationId, String rationale) {
        return approve(obligationId, rationale, Map.of());
    }

    /**
     * Approves an application. Every rule of the credit policy version in force must pass;
     * the decision cites that version and captures a snapshot of what was known.
     *
     * @param suppliedEvidence information the decider relied on that the bank does not
     *                         hold itself (e.g. declared income); stored as unverified
     */
    @Transactional
    public Obligation approve(UUID obligationId, String rationale, Map<String, String> suppliedEvidence) {
        OperationContext context = RequestContext.forOperation("loan.approve");
        Obligation o = lock(obligationId);
        InvalidStateTransitionException.require(o.getStatus().canTransitionTo(ObligationStatus.APPROVED), "obligation",
                obligationId, o.getStatus(), ObligationStatus.APPROVED);
        DecisionBasis basis = decisionBasis(o, LoanDecision.Kind.APPROVED);
        List<PolicyEngine.RuleResult> failed = basis.results().stream().filter(r -> !r.passed()).toList();
        if (!failed.isEmpty()) {
            throw new BusinessRuleViolationException("loan.policy_not_met", "%s v%d not met: %s".formatted(
                    basis.policy().getPolicyCode(), basis.policy().getVersion(), failed.stream()
                            .map(r -> r.rule() + " (observed " + r.observed() + ", limit " + r.threshold() + ")")
                            .collect(Collectors.joining("; "))));
        }
        ObligationStatus from = o.getStatus();
        o.approve(clock.instant());
        obligations.save(o);
        decide(o, LoanDecision.Kind.APPROVED, rationale, suppliedEvidence, basis, from, context);
        return o;
    }

    @Transactional
    public Obligation decline(UUID obligationId, String rationale) {
        return decline(obligationId, rationale, Map.of());
    }

    @Transactional
    public Obligation decline(UUID obligationId, String rationale, Map<String, String> suppliedEvidence) {
        OperationContext context = RequestContext.forOperation("loan.decline");
        Obligation o = lock(obligationId);
        InvalidStateTransitionException.require(o.getStatus().canTransitionTo(ObligationStatus.DECLINED), "obligation",
                obligationId, o.getStatus(), ObligationStatus.DECLINED);
        DecisionBasis basis = decisionBasis(o, LoanDecision.Kind.DECLINED);
        ObligationStatus from = o.getStatus();
        o.decline(clock.instant());
        obligations.saveAndFlush(o);
        accounts.closeInternal(o.getPositionAccountId());
        decide(o, LoanDecision.Kind.DECLINED, rationale, suppliedEvidence, basis, from, context);
        return o;
    }

    /** The applicant withdraws, before or after approval but before disbursement. */
    @Transactional
    public Obligation cancel(UUID obligationId) {
        OperationContext context = RequestContext.forOperation("loan.cancel");
        Obligation o = lock(obligationId);
        o.cancel(clock.instant());
        obligations.saveAndFlush(o);
        accounts.closeInternal(o.getPositionAccountId());
        auditTrail.record("loan.cancelled", "obligation", obligationId, context, Map.of());
        history.record(obligationId, LendingEventType.APPLICATION_CANCELLED, o.getCancelledAt(), Map.of(), null, null,
                context);
        return o;
    }

    // ================================================== origination & disbursement

    /**
     * Originates the agreement and releases the principal, atomically: the schedule becomes
     * binding from today, the loan's principal and interest-receivable positions are opened
     * in the ledger, and Dr loan receivable / Cr borrower deposit is posted. Idempotent: an
     * already-disbursed loan is returned unchanged.
     */
    @Transactional
    public Obligation disburse(UUID obligationId) {
        OperationContext context = RequestContext.forOperation("loan.disburse");
        Obligation o = lock(obligationId);
        if (o.getStatus().isServiced() || o.getStatus() == ObligationStatus.SETTLED) {
            return o;
        }
        InvalidStateTransitionException.require(o.getStatus() == ObligationStatus.APPROVED, "obligation",
                obligationId, o.getStatus(), ObligationStatus.ACTIVE);
        Account settlement = accounts.require(o.getSettlementAccountId());
        if (!settlement.isOperable()) {
            throw new BusinessRuleViolationException("loan.settlement_not_usable",
                    "Settlement account %s is %s".formatted(settlement.getAccountNumber(), settlement.getStatus()));
        }
        LoanTerms loanTerms = requireTerms(obligationId);
        CurrencyUnit currency = currencies.require(o.getCurrencyCode());
        Money principal = Money.ofMinorUnits(o.getPrincipalMinor(), currency);
        LocalDate start = LocalDate.now(clock);

        Account position = accounts.activateInternal(o.getPositionAccountId());
        UUID receivable = null;
        if (loanTerms.isAccrualBasis()) {
            receivable = ledger.openAccount("1310-LOAN-INT-" + position.getAccountNumber(),
                    "Accrued interest " + o.getObligationNumber(), LedgerAccountType.ASSET, currency,
                    LedgerAccountPurpose.LOAN_INTEREST_RECEIVABLE, 0L).getId();
        }
        List<AnnuitySchedule.Line> schedule = AnnuitySchedule.generate(o.getPrincipalMinor(),
                loanTerms.getAnnualRateBps(), loanTerms.getInstallmentCount(), start);
        schedule.forEach(line -> installments.save(Installment.of(obligationId, line)));

        PostedEntry posted = ledger.post(new JournalEntryRequest(JournalEntryType.LOAN_DISBURSEMENT,
                "Disbursement of loan " + o.getObligationNumber(), start, currency,
                List.of(PostingInstruction.debit(position.getLedgerAccountId(), principal),
                        PostingInstruction.credit(settlement.getLedgerAccountId(), principal)),
                "loan.disbursement:" + obligationId, context));

        o.activate(start, schedule.get(schedule.size() - 1).dueDate(), posted.entry().getId(), receivable,
                clock.instant());
        obligations.save(o);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("journalEntryId", posted.entry().getId().toString());
        payload.put("principalMinor", principal.minorUnits());
        payload.put("startDate", start.toString());
        payload.put("maturityDate", o.getMaturityDate().toString());
        payload.put("installments", schedule.size());
        payload.put("instalmentMinor", AnnuitySchedule.instalment(o.getPrincipalMinor(),
                loanTerms.getAnnualRateBps(), loanTerms.getInstallmentCount()));
        auditTrail.record("loan.disbursed", "obligation", obligationId, context, payload);
        Map<String, Object> disbursed = new LinkedHashMap<>();
        disbursed.put("journalEntryId", posted.entry().getId().toString());
        disbursed.put("principalMinor", principal.minorUnits());
        disbursed.put("startDate", start.toString());
        disbursed.put("interestReceivableLedgerAccountId", receivable == null ? null : receivable.toString());
        history.record(obligationId, LendingEventType.LOAN_DISBURSED, o.getActivatedAt(), disbursed, null, null, context);
        history.record(obligationId, LendingEventType.SCHEDULE_ESTABLISHED, o.getActivatedAt(), Map.of(
                "maturityDate", o.getMaturityDate().toString(),
                "installments", schedule.stream().map(l -> Map.of("sequence", l.sequence(),
                        "dueDate", l.dueDate().toString(), "principalMinor", l.principalMinor(),
                        "interestMinor", l.interestMinor())).toList()), null, null, context);
        return o;
    }

    // ================================================================= repayment

    @Transactional
    public RepaymentResult repay(UUID obligationId, Money amount, String idempotencyKey) {
        OperationContext context = RequestContext.forOperation("loan.repay");
        Obligation o = lock(obligationId);
        String key = idempotencyKey == null ? null : "loan.repayment:" + idempotencyKey.strip();
        if (key != null) {
            var existing = repayments.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                Repayment r = existing.get();
                if (!r.getObligationId().equals(obligationId) || r.getAmountMinor() != amount.minorUnits()) {
                    throw new ConflictException("loan.idempotency_key_reused",
                            "Idempotency key %s was already used for a different repayment".formatted(idempotencyKey));
                }
                return new RepaymentResult(r, List.of(), true, o.getStatus() == ObligationStatus.SETTLED);
            }
        }
        InvalidStateTransitionException.require(o.getStatus().isServiced(), "obligation", obligationId,
                o.getStatus(), ObligationStatus.SETTLED);
        if (!amount.currency().code().equals(o.getCurrencyCode())) {
            throw new CurrencyMismatchException("Repayment in %s against a %s loan"
                    .formatted(amount.currency().code(), o.getCurrencyCode()));
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A repayment must be positive");
        }

        LoanTerms loanTerms = requireTerms(obligationId);
        LocalDate today = LocalDate.now(clock);
        accrueUpTo(o, loanTerms, today, context); // interest of every ended period is earned first

        List<Installment> schedule = installments.findByObligationIdOrderBySequenceNoAsc(obligationId);
        Map<UUID, long[]> paid = paid(schedule);
        Map<UUID, Long> waived = waived(obligationId);
        AllocationPolicy.Result allocation;
        List<AllocationPolicy.Waiver> newWaivers = List.of();
        if (AllocationPolicy.V2.equals(loanTerms.getAllocationPolicy())) {
            List<AllocationPolicy.Open> open = new ArrayList<>();
            for (Installment i : schedule) {
                long[] p = paid.getOrDefault(i.getId(), new long[2]);
                long pu = i.getPrincipalDueMinor() - p[0];
                long iu = i.getInterestDueMinor() - p[1] - waived.getOrDefault(i.getId(), 0L);
                if (pu + iu > 0) {
                    open.add(new AllocationPolicy.Open(i.getId(), i.getSequenceNo(), !i.getDueDate().isAfter(today),
                            pu, iu));
                }
            }
            AllocationPolicy.Quote quote = AllocationPolicy.quoteV2(open);
            if (quote.dueNowMinor() == 0 && amount.minorUnits() != quote.payoffMinor()) {
                throw new BusinessRuleViolationException("loan.nothing_due",
                        "Nothing is due on loan %s until %s; early settlement amount is %d"
                                .formatted(o.getObligationNumber(), nextDueDate(schedule, paid, waived),
                                        quote.payoffMinor()));
            }
            try {
                AllocationPolicy.V2Result r = AllocationPolicy.allocateV2(open, amount.minorUnits());
                allocation = r.result();
                newWaivers = r.waivers();
            } catch (AllocationPolicy.PrepaymentNotSupported e) {
                throw new BusinessRuleViolationException("loan.prepayment_not_supported", e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("loan.overpayment", e.getMessage());
            }
        } else {
            List<AllocationPolicy.Owed> owed = owedV1(schedule, paid);
            long totalOwed = owed.stream().mapToLong(x -> x.principalMinor() + x.interestMinor()).sum();
            if (amount.minorUnits() > totalOwed) {
                throw new BusinessRuleViolationException("loan.overpayment",
                        "Repayment %s exceeds the %d minor units still owed".formatted(amount, totalOwed));
            }
            allocation = AllocationPolicy.allocateV1(owed, amount.minorUnits());
        }

        Account settlement = accounts.require(o.getSettlementAccountId());
        Account position = accounts.require(o.getPositionAccountId());
        funds.requireAvailable(settlement, amount); // cannot repay with money held for a payment
        CurrencyUnit currency = amount.currency();
        List<PostingInstruction> legs = new ArrayList<>();
        legs.add(PostingInstruction.debit(settlement.getLedgerAccountId(), amount));
        if (allocation.principalMinor() > 0) {
            legs.add(PostingInstruction.credit(position.getLedgerAccountId(),
                    Money.ofMinorUnits(allocation.principalMinor(), currency)));
        }
        if (allocation.interestMinor() > 0) {
            UUID interestTarget = loanTerms.isAccrualBasis()
                    ? o.getInterestReceivableLedgerAccountId() // settles interest already earned
                    : interestIncome(currency);                // cash basis: income on receipt
            legs.add(PostingInstruction.credit(interestTarget,
                    Money.ofMinorUnits(allocation.interestMinor(), currency)));
        }
        PostedEntry posted = ledger.post(new JournalEntryRequest(JournalEntryType.LOAN_REPAYMENT,
                "Repayment of loan " + o.getObligationNumber(), today, currency, legs, key, context));
        if (posted.replayed()) {
            throw new IllegalStateException("Ledger entry exists for " + key + " without a repayment record");
        }

        Instant now = clock.instant();
        Repayment repayment = repayments.save(
                Repayment.record(UUID.randomUUID(), o, posted.entry().getId(), key, allocation, now));
        List<RepaymentAllocation> rows = allocation.allocations().stream()
                .map(a -> allocations.save(RepaymentAllocation.of(repayment.getId(), a))).toList();
        newWaivers.forEach(w -> waivers.save(InstallmentWaiver.earlySettlement(obligationId, repayment.getId(), w, now)));
        Map<UUID, Integer> seqOf = schedule.stream()
                .collect(Collectors.toMap(Installment::getId, Installment::getSequenceNo));
        Map<String, Object> received = new LinkedHashMap<>();
        received.put("repaymentId", repayment.getId().toString());
        received.put("journalEntryId", posted.entry().getId().toString());
        received.put("amountMinor", allocation.totalMinor());
        received.put("principalMinor", allocation.principalMinor());
        received.put("interestMinor", allocation.interestMinor());
        received.put("allocationPolicy", allocation.policy());
        received.put("allocations", allocation.allocations().stream().map(a -> Map.of("sequence", a.sequence(),
                "principalMinor", a.principalMinor(), "interestMinor", a.interestMinor())).toList());
        received.put("waivers", newWaivers.stream().map(w -> Map.of("sequence", seqOf.get(w.installmentId()),
                "interestMinor", w.interestMinor())).toList());
        history.record(obligationId, LendingEventType.REPAYMENT_RECEIVED, now, received, "obligation_repayment",
                repayment.getId(), context);

        boolean satisfied = isFullySatisfied(obligationId);
        if (satisfied) {
            o.settle(now);
        }
        obligations.saveAndFlush(o);
        if (satisfied) {
            history.record(obligationId, LendingEventType.LOAN_SETTLED, now, Map.of(), null, null, context);
            accounts.closeInternal(position.getId());
            if (o.getInterestReceivableLedgerAccountId() != null) {
                ledger.changeAccountStatus(o.getInterestReceivableLedgerAccountId(), LedgerAccountStatus.CLOSED);
            }
        } else {
            recordDelinquencyChange(o, today, context);
        }
        assertLedgerAgrees(o, position);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("repaymentId", repayment.getId().toString());
        payload.put("journalEntryId", posted.entry().getId().toString());
        payload.put("amountMinor", amount.minorUnits());
        payload.put("principalMinor", allocation.principalMinor());
        payload.put("interestMinor", allocation.interestMinor());
        payload.put("interestWaivedMinor", newWaivers.stream().mapToLong(AllocationPolicy.Waiver::interestMinor).sum());
        payload.put("allocationPolicy", allocation.policy());
        payload.put("settled", satisfied);
        auditTrail.record("loan.repaid", "obligation", obligationId, context, payload);
        return new RepaymentResult(repayment, rows, false, satisfied);
    }

    // ================================================================= servicing

    /**
     * End-of-day servicing of one loan as of {@code asOf}: books the interest of every
     * period that has ended, and records a change of delinquency bucket if there is one.
     * {@code asOf} may not be in the future: interest is never recognised before it is earned.
     */
    @Transactional
    public ServicingResult service(UUID obligationId, LocalDate asOf) {
        if (asOf.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("Cannot service a loan as of a future date: " + asOf);
        }
        OperationContext context = RequestContext.forOperation("loan.service");
        Obligation o = lock(obligationId);
        if (!o.getStatus().isServiced()) {
            return new ServicingResult(obligationId, 0, null, false);
        }
        if (asOf.isBefore(o.getStartDate())) {
            return new ServicingResult(obligationId, 0, null, false); // nothing can have happened yet
        }
        int posted = accrueUpTo(o, requireTerms(obligationId), asOf, context);
        Delinquency.Status status = delinquency(o.getId(), asOf);
        boolean changed = recordDelinquencyChange(o, asOf, context);
        assertLedgerAgrees(o, accounts.require(o.getPositionAccountId()));
        return new ServicingResult(obligationId, posted, status, changed);
    }

    /**
     * Declares default: a credit decision, permitted only when the loan is at least
     * {@value Delinquency#DEFAULT_THRESHOLD_DAYS} days past due. No ledger effect in this model
     * (impairment and provisioning are out of scope); repayments continue to be accepted.
     */
    @Transactional
    public Obligation declareDefault(UUID obligationId, String rationale) {
        OperationContext context = RequestContext.forOperation("loan.declare_default");
        Obligation o = lock(obligationId);
        InvalidStateTransitionException.require(o.getStatus() == ObligationStatus.ACTIVE, "obligation", obligationId,
                o.getStatus(), ObligationStatus.DEFAULTED);
        LocalDate today = LocalDate.now(clock);
        accrueUpTo(o, requireTerms(obligationId), today, context);
        DecisionBasis basis = decisionBasis(o, LoanDecision.Kind.DEFAULT_DECLARED);
        Delinquency.Status s = delinquency(obligationId, today);
        if (!basis.results().stream().allMatch(PolicyEngine.RuleResult::passed)) {
            throw new BusinessRuleViolationException("loan.default_threshold_not_met",
                    "Loan %s is %d days past due; %s v%d requires %s".formatted(o.getObligationNumber(),
                            s.daysPastDue(), basis.policy().getPolicyCode(), basis.policy().getVersion(),
                            basis.results().get(0).threshold()));
        }
        recordDelinquencyChange(o, today, context);
        ObligationStatus from = o.getStatus();
        o.declareDefault(clock.instant());
        obligations.save(o);
        decide(o, LoanDecision.Kind.DEFAULT_DECLARED, rationale, Map.of(), basis, from, context);
        return o;
    }

    // ===================================================================== reads

    @Transactional(readOnly = true)
    public LoanView view(UUID obligationId) {
        Obligation o = obligations.findById(obligationId).orElseThrow(() -> NotFoundException.of("obligation", obligationId));
        LoanTerms loanTerms = requireTerms(obligationId);
        List<Installment> schedule = installments.findByObligationIdOrderBySequenceNoAsc(obligationId);
        Map<UUID, long[]> paid = paid(schedule);
        Map<UUID, Long> waived = waived(obligationId);
        Set<UUID> accrued = accruals.findByObligationId(obligationId).stream()
                .map(InterestAccrual::getInstallmentId).collect(Collectors.toSet());
        LocalDate today = LocalDate.now(clock);
        List<LoanView.InstallmentView> lines = schedule.stream().map(i -> {
            long[] p = paid.getOrDefault(i.getId(), new long[2]);
            return new LoanView.InstallmentView(i.getSequenceNo(), i.getDueDate(), i.getPrincipalDueMinor(),
                    i.getInterestDueMinor(), p[0], p[1], waived.getOrDefault(i.getId(), 0L), accrued.contains(i.getId()));
        }).toList();
        Account position = accounts.require(o.getPositionAccountId());
        Long ledgerPrincipal = position.hasLedgerPosition()
                ? ledger.requireBalance(position.getLedgerAccountId()).getBalanceMinor() : null;
        Long ledgerReceivable = o.getInterestReceivableLedgerAccountId() == null ? null
                : ledger.requireBalance(o.getInterestReceivableLedgerAccountId()).getBalanceMinor();
        long accruedTotal = accruals.findByObligationId(obligationId).stream().mapToLong(InterestAccrual::getAmountMinor).sum();
        List<Repayment> history = repayments.findByObligationIdOrderByReceivedAtAsc(obligationId);
        Delinquency.Status delinquency = o.getStatus().isServiced() ? delinquency(obligationId, today) : null;
        AllocationPolicy.Quote quote = null;
        if (o.getStatus().isServiced() && AllocationPolicy.V2.equals(loanTerms.getAllocationPolicy())) {
            // Quote as of today, counting interest of ended periods as due even if not yet booked.
            List<AllocationPolicy.Open> open = new ArrayList<>();
            for (LoanView.InstallmentView l : lines) {
                long pu = l.principalDueMinor() - l.principalPaidMinor();
                long iu = l.interestDueMinor() - l.interestPaidMinor() - l.interestWaivedMinor();
                if (pu + iu > 0) {
                    open.add(new AllocationPolicy.Open(null, l.sequence(), !l.dueDate().isAfter(today), pu, iu));
                }
            }
            quote = AllocationPolicy.quoteV2(open);
        }
        return new LoanView(o, loanTerms, lines, ledgerPrincipal, ledgerReceivable, accruedTotal, history,
                delinquency, quote, decisions.findByObligationIdOrderByDecidedAtAsc(obligationId),
                delinquencyEvents.findByObligationIdOrderByRecordedAtAsc(obligationId));
    }

    // ================================================================= internals

    /** Books the interest of every instalment whose period ended on or before {@code asOf}. */
    private int accrueUpTo(Obligation o, LoanTerms loanTerms, LocalDate asOf, OperationContext context) {
        if (!loanTerms.isAccrualBasis()) {
            return 0;
        }
        Set<UUID> done = accruals.findByObligationId(o.getId()).stream()
                .map(InterestAccrual::getInstallmentId).collect(Collectors.toSet());
        Set<UUID> waivedIds = waived(o.getId()).keySet();
        CurrencyUnit currency = currencies.require(o.getCurrencyCode());
        int posted = 0;
        for (Installment i : installments.findByObligationIdOrderBySequenceNoAsc(o.getId())) {
            if (i.getDueDate().isAfter(asOf) || i.getInterestDueMinor() == 0 || done.contains(i.getId())
                    || waivedIds.contains(i.getId())) {
                continue;
            }
            Money interest = Money.ofMinorUnits(i.getInterestDueMinor(), currency);
            PostedEntry entry = ledger.post(new JournalEntryRequest(JournalEntryType.INTEREST_ACCRUAL,
                    "Interest accrued on loan %s, instalment %d".formatted(o.getObligationNumber(), i.getSequenceNo()),
                    i.getDueDate(), currency,
                    List.of(PostingInstruction.debit(o.getInterestReceivableLedgerAccountId(), interest),
                            PostingInstruction.credit(interestIncome(currency), interest)),
                    "loan.accrual:" + i.getId(), context));
            InterestAccrual accrual = accruals.save(InterestAccrual.of(i, entry.entry().getId(), clock.instant()));
            history.record(o.getId(), LendingEventType.INTEREST_ACCRUED, LendingHistory.startOfDay(i.getDueDate()),
                    Map.of("accrualId", accrual.getId().toString(), "installmentSequence", (int) i.getSequenceNo(),
                            "amountMinor", i.getInterestDueMinor(), "accrualDate", i.getDueDate().toString(),
                            "journalEntryId", entry.entry().getId().toString()),
                    "interest_accrual", accrual.getId(), context);
            posted++;
        }
        return posted;
    }

    private Delinquency.Status delinquency(UUID obligationId, LocalDate asOf) {
        List<Installment> schedule = installments.findByObligationIdOrderBySequenceNoAsc(obligationId);
        Map<UUID, long[]> paid = paid(schedule);
        Map<UUID, Long> waived = waived(obligationId);
        return Delinquency.evaluate(schedule.stream().map(i -> {
            long[] p = paid.getOrDefault(i.getId(), new long[2]);
            return new Delinquency.Line(i.getSequenceNo(), i.getDueDate(), i.getPrincipalDueMinor(),
                    i.getInterestDueMinor(), p[0], p[1], waived.getOrDefault(i.getId(), 0L));
        }).toList(), asOf);
    }

    /** Appends a delinquency event if the bucket differs from the last one recorded. */
    private boolean recordDelinquencyChange(Obligation o, LocalDate asOf, OperationContext context) {
        Delinquency.Status s = delinquency(o.getId(), asOf);
        List<DelinquencyEvent> observed = delinquencyEvents.findByObligationIdOrderByRecordedAtAsc(o.getId());
        if (!observed.isEmpty() && asOf.isBefore(observed.get(observed.size() - 1).getAsOf())) {
            return false; // observations are chronological: never record an older view after a newer one
        }
        Delinquency.Bucket last = observed.isEmpty() ? Delinquency.Bucket.CURRENT
                : observed.get(observed.size() - 1).getToBucket();
        if (last == s.bucket()) {
            return false;
        }
        DelinquencyEvent event = delinquencyEvents.save(DelinquencyEvent.of(o.getId(), last, s, clock.instant()));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asOf", asOf.toString());
        payload.put("fromBucket", last.name());
        payload.put("toBucket", s.bucket().name());
        payload.put("daysPastDue", s.daysPastDue());
        payload.put("overduePrincipalMinor", s.overduePrincipalMinor());
        payload.put("overdueInterestMinor", s.overdueInterestMinor());
        history.record(o.getId(), LendingEventType.DELINQUENCY_CHANGED, LendingHistory.startOfDay(asOf), payload,
                "delinquency_event", event.getId(), context);
        return true;
    }

    private boolean isFullySatisfied(UUID obligationId) {
        List<Installment> schedule = installments.findByObligationIdOrderBySequenceNoAsc(obligationId);
        Map<UUID, long[]> paid = paid(schedule);
        Map<UUID, Long> waived = waived(obligationId);
        return schedule.stream().allMatch(i -> {
            long[] p = paid.getOrDefault(i.getId(), new long[2]);
            return p[0] == i.getPrincipalDueMinor()
                    && p[1] + waived.getOrDefault(i.getId(), 0L) == i.getInterestDueMinor();
        });
    }

    private static LocalDate nextDueDate(List<Installment> schedule, Map<UUID, long[]> paid, Map<UUID, Long> waived) {
        for (Installment i : schedule) {
            long[] p = paid.getOrDefault(i.getId(), new long[2]);
            if (p[0] < i.getPrincipalDueMinor() || p[1] + waived.getOrDefault(i.getId(), 0L) < i.getInterestDueMinor()) {
                return i.getDueDate();
            }
        }
        return null;
    }

    private List<AllocationPolicy.Owed> owedV1(List<Installment> schedule, Map<UUID, long[]> paid) {
        return schedule.stream().map(i -> {
            long[] p = paid.getOrDefault(i.getId(), new long[2]);
            return new AllocationPolicy.Owed(i.getId(), i.getSequenceNo(),
                    i.getPrincipalDueMinor() - p[0], i.getInterestDueMinor() - p[1]);
        }).filter(x -> x.principalMinor() + x.interestMinor() > 0).toList();
    }

    private Map<UUID, long[]> paid(List<Installment> schedule) {
        Map<UUID, long[]> paid = new HashMap<>();
        if (schedule.isEmpty()) {
            return paid;
        }
        for (Object[] row : allocations.paidByInstallment(schedule.stream().map(Installment::getId).toList())) {
            paid.put((UUID) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return paid;
    }

    private Map<UUID, Long> waived(UUID obligationId) {
        Map<UUID, Long> m = new HashMap<>();
        waivers.findByObligationId(obligationId).forEach(w -> m.put(w.getInstallmentId(), w.getInterestWaivedMinor()));
        return m;
    }

    private UUID interestIncome(CurrencyUnit currency) {
        return ledger.requireAccountByCode("4000-INTEREST-INCOME-" + currency.code()).getId();
    }

    private Obligation lock(UUID obligationId) {
        return obligations.findByIdForUpdate(obligationId)
                .orElseThrow(() -> NotFoundException.of("obligation", obligationId));
    }

    private LoanTerms requireTerms(UUID obligationId) {
        return terms.findById(obligationId).orElseThrow(() -> NotFoundException.of("loan_terms", obligationId));
    }

    private Map<String, Object> termsEvidence(Obligation o, LoanTerms t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("obligationNumber", o.getObligationNumber());
        m.put("creditorPartyId", o.getCreditorPartyId().toString());
        m.put("debtorPartyId", o.getDebtorPartyId().toString());
        m.put("principalMinor", o.getPrincipalMinor());
        m.put("currency", o.getCurrencyCode());
        m.put("annualRateBps", t.getAnnualRateBps());
        m.put("installmentCount", t.getInstallmentCount());
        m.put("repaymentFrequency", t.getRepaymentFrequency());
        m.put("amortizationMethod", t.getAmortizationMethod());
        m.put("allocationPolicy", t.getAllocationPolicy());
        m.put("interestRecognition", t.getInterestRecognition().name());
        m.put("instalmentMinor", AnnuitySchedule.instalment(o.getPrincipalMinor(), t.getAnnualRateBps(),
                t.getInstallmentCount()));
        return m;
    }

    /** The policy in force at decision time, its rule results, and the facts they were evaluated on. */
    private record DecisionBasis(CreditPolicy policy, CreditPolicyRules rules,
                                 List<PolicyEngine.RuleResult> results, Customer customer, Party party,
                                 List<Map<String, Object>> existingObligations, Map<String, Object> subjectLoan) {}

    private DecisionBasis decisionBasis(Obligation o, LoanDecision.Kind kind) {
        LocalDate today = LocalDate.now(clock);
        CreditPolicy policy = policies.inForceAt(clock.instant());
        CreditPolicyRules rules = policies.rules(policy);
        Account position = accounts.require(o.getPositionAccountId());
        Customer customer = customers.require(position.getCustomerId());
        Party party = parties.require(o.getDebtorPartyId());
        List<Map<String, Object>> existing = existingObligations(o, today);
        Map<String, Object> subjectLoan = null;
        List<PolicyEngine.RuleResult> results;
        if (kind == LoanDecision.Kind.DEFAULT_DECLARED) {
            Delinquency.Status s = delinquency(o.getId(), today);
            subjectLoan = new LinkedHashMap<>();
            subjectLoan.put("status", o.getStatus().name());
            subjectLoan.put("outstandingPrincipalMinor",
                    ledger.requireBalance(position.getLedgerAccountId()).getBalanceMinor());
            subjectLoan.put("delinquency", delinquencyMap(s));
            results = rules.evaluateDefault(s.daysPastDue()).results();
        } else {
            LoanTerms t = requireTerms(o.getId());
            Long settlementAvailable = funds.balances(o.getSettlementAccountId()).availableMinor();
            results = rules.evaluateApproval(new DecisionFacts(customer.getStatus().name(), o.getCurrencyCode(),
                    currencies.require(o.getCurrencyCode()).minorUnit(), o.getPrincipalMinor(),
                    (long) t.getInstallmentCount(), (long) t.getAnnualRateBps(),
                    existing.stream().anyMatch(e -> "DEFAULTED".equals(e.get("status"))),
                    existing.stream().mapToLong(e -> (Long) e.get("daysPastDue")).max().orElse(0),
                    settlementAvailable, null)).results();
        }
        return new DecisionBasis(policy, rules, results, customer, party, existing, subjectLoan);
    }

    /** The debtor's other obligations as they stand now (i.e. at decision time), from live state. */
    private List<Map<String, Object>> existingObligations(Obligation subject, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Obligation other : obligations.findByDebtorPartyIdOrderByProposedAtAsc(subject.getDebtorPartyId())) {
            if (other.getId().equals(subject.getId())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("obligationId", other.getId().toString());
            m.put("obligationNumber", other.getObligationNumber());
            m.put("status", other.getStatus().name());
            m.put("principalMinor", other.getPrincipalMinor());
            Account pos = accounts.require(other.getPositionAccountId());
            m.put("outstandingPrincipalMinor", pos.hasLedgerPosition()
                    ? ledger.requireBalance(pos.getLedgerAccountId()).getBalanceMinor() : 0L);
            long dpd = other.getStatus().isServiced() ? delinquency(other.getId(), today).daysPastDue() : 0L;
            m.put("daysPastDue", dpd);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> delinquencyMap(Delinquency.Status s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", s.asOf().toString());
        m.put("daysPastDue", s.daysPastDue());
        m.put("bucket", s.bucket().name());
        m.put("overduePrincipalMinor", s.overduePrincipalMinor());
        m.put("overdueInterestMinor", s.overdueInterestMinor());
        return m;
    }

    /**
     * Records a credit decision: the decision row, its snapshot (what was known), and the
     * history event. All three commit with the state change they justify.
     */
    private void decide(Obligation o, LoanDecision.Kind kind, String rationale, Map<String, String> suppliedEvidence,
                        DecisionBasis basis, ObligationStatus from, OperationContext context) {
        Instant now = clock.instant();
        Map<String, String> supplied = suppliedEvidence == null ? Map.of() : new java.util.TreeMap<>(suppliedEvidence);
        List<Map<String, Object>> ruleResults = basis.results().stream().map(PolicyEngine.RuleResult::asMap).toList();
        Map<String, Object> decisionEvidence = new LinkedHashMap<>();
        decisionEvidence.put("ruleEvaluation", ruleResults);
        decisionEvidence.put("suppliedEvidence", supplied);
        if (basis.subjectLoan() != null) {
            decisionEvidence.putAll(basis.subjectLoan());
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) basis.subjectLoan().get("delinquency");
            decisionEvidence.put("daysPastDue", d.get("daysPastDue"));
        } else {
            decisionEvidence.putAll(termsEvidence(o, requireTerms(o.getId())));
        }
        LoanDecision decision;
        try {
            decision = decisions.save(LoanDecision.record(o.getId(), kind, rationale,
                    json.writeValueAsString(decisionEvidence), basis.policy().getPolicyCode(),
                    basis.policy().getVersion(), context, now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }

        CreditDecisionSnapshot snapshot = snapshots.capture(decision.getId(), o.getId(), basis.policy(), now,
                snapshotContent(o, kind, rationale, supplied, basis, ruleResults, from, context, now));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("decisionId", decision.getId().toString());
        event.put("decision", kind == LoanDecision.Kind.DEFAULT_DECLARED ? "DEFAULTED" : kind.name());
        event.put("rationale", rationale);
        event.put("policyCode", basis.policy().getPolicyCode());
        event.put("policyVersion", basis.policy().getVersion());
        event.put("snapshotId", snapshot.getId().toString());
        event.put("snapshotSha256", snapshot.getContentSha256());
        event.put("evidence", supplied);
        history.record(o.getId(), kind == LoanDecision.Kind.DEFAULT_DECLARED ? LendingEventType.DEFAULT_DECLARED
                : LendingEventType.CREDIT_DECISION, now, event, "loan_decision", decision.getId(), context);

        Map<String, Object> payload = new LinkedHashMap<>(event);
        auditTrail.record("loan." + kind.name().toLowerCase(), "obligation", o.getId(), context, payload);
    }

    /**
     * The decision snapshot. Every section answers one of the questions a later reviewer
     * must be able to answer about this decision; nothing is captured "in case".
     */
    private Map<String, Object> snapshotContent(Obligation o, LoanDecision.Kind kind, String rationale,
                                                Map<String, String> supplied, DecisionBasis basis,
                                                List<Map<String, Object>> ruleResults, ObligationStatus from,
                                                OperationContext context, Instant now) {
        LoanTerms t = requireTerms(o.getId());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("schema", DecisionSnapshots.SCHEMA);

        Map<String, Object> decision = new LinkedHashMap<>();                 // what, when, who
        decision.put("kind", kind.name());
        decision.put("decidedAt", now.toString());
        decision.put("decidedBy", context.actor());
        decision.put("deciderType", context.actorType().name());
        decision.put("correlationId", context.correlationId().toString());
        decision.put("rationale", rationale);
        c.put("decision", decision);

        Map<String, Object> subject = new LinkedHashMap<>();                  // who was the subject
        subject.put("partyId", basis.party().getId().toString());
        subject.put("partyType", basis.party().type().name());
        subject.put("countryCode", basis.party().getCountryCode());
        subject.put("customerId", basis.customer().getId().toString());
        subject.put("customerNumber", basis.customer().getCustomerNumber());
        subject.put("customerStatus", basis.customer().getStatus().name());
        subject.put("relationshipActivatedAt", String.valueOf(basis.customer().getActivatedAt()));
        c.put("subject", subject);

        Map<String, Object> application = termsEvidence(o, t);               // what was being considered
        application.put("obligationId", o.getId().toString());
        application.put("productCode", accounts.require(o.getPositionAccountId()).getProductCode());
        application.put("proposedAt", o.getProposedAt().toString());
        application.put("settlementAccountId", o.getSettlementAccountId().toString());
        c.put("application", application);

        List<Map<String, Object>> accountState = new ArrayList<>();          // relevant account state
        for (Account a : accounts.listByCustomer(basis.customer().getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", a.getId().toString());
            m.put("productCode", a.getProductCode());
            m.put("status", a.getStatus().name());
            m.put("currency", a.getCurrencyCode());
            FundsService.Balances b = funds.balances(a.getId());
            m.put("ledgerBalanceMinor", b.ledgerBalanceMinor());
            m.put("reservedMinor", b.reservedMinor());
            m.put("availableMinor", b.availableMinor());
            accountState.add(m);
        }
        c.put("accounts", accountState);
        c.put("existingObligations", basis.existingObligations());           // obligations already held
        if (basis.subjectLoan() != null) {
            c.put("subjectLoan", basis.subjectLoan());                        // default decisions: the loan itself
        }

        Map<String, Object> evidence = new LinkedHashMap<>();                 // evidence used
        evidence.put("suppliedByDecider", supplied);
        evidence.put("nature", "UNVERIFIED_DECLARATIONS");
        c.put("evidence", evidence);

        Map<String, Object> policy = new LinkedHashMap<>();                   // rules in force
        policy.put("code", basis.policy().getPolicyCode());
        policy.put("version", basis.policy().getVersion());
        policy.put("effectiveFrom", basis.policy().getEffectiveFrom().toString());
        policy.put("rules", json.valueToTree(basis.rules()));
        c.put("policy", policy);
        c.put("ruleEvaluation", ruleResults);

        Map<String, Object> logic = new LinkedHashMap<>();                    // how the decision was produced
        logic.put("type", "POLICY_RULES_WITH_HUMAN_JUDGEMENT");
        logic.put("policyCode", basis.policy().getPolicyCode());
        logic.put("policyVersion", basis.policy().getVersion());
        c.put("decisionLogic", logic);

        Map<String, Object> action = new LinkedHashMap<>();                  // resulting action
        action.put("fromStatus", from.name());
        action.put("toStatus", o.getStatus().name());
        c.put("resultingAction", action);
        return c;
    }

    /** Runtime cross-check of the central invariants before PostgreSQL checks them again at COMMIT. */
    private void assertLedgerAgrees(Obligation o, Account position) {
        List<Repayment> history = repayments.findByObligationIdOrderByReceivedAtAsc(o.getId());
        long repaidPrincipal = history.stream().mapToLong(Repayment::getPrincipalMinor).sum();
        long ledgerBalance = ledger.requireBalance(position.getLedgerAccountId()).getBalanceMinor();
        if (ledgerBalance != o.getPrincipalMinor() - repaidPrincipal) {
            throw new IllegalStateException("Loan %s: ledger position %d != principal %d - repaid %d".formatted(
                    o.getObligationNumber(), ledgerBalance, o.getPrincipalMinor(), repaidPrincipal));
        }
        if (o.getInterestReceivableLedgerAccountId() != null) {
            long accrued = accruals.findByObligationId(o.getId()).stream().mapToLong(InterestAccrual::getAmountMinor).sum();
            long repaidInterest = history.stream().mapToLong(Repayment::getInterestMinor).sum();
            long receivable = ledger.requireBalance(o.getInterestReceivableLedgerAccountId()).getBalanceMinor();
            if (receivable != accrued - repaidInterest) {
                throw new IllegalStateException("Loan %s: ledger interest receivable %d != accrued %d - paid %d"
                        .formatted(o.getObligationNumber(), receivable, accrued, repaidInterest));
            }
        }
    }
}
