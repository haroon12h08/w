package com.wbank.obligation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.LoanService;
import com.wbank.obligation.LoanServicingService;
import com.wbank.obligation.LoanView;
import com.wbank.obligation.domain.Delinquency;
import com.wbank.obligation.domain.Obligation;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan lifecycle actions. There is no endpoint that edits principal, rate, schedule,
 * balances or repayment status: those change only as consequences of named operations.
 */
@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanService loans;
    private final LoanServicingService servicing;
    private final CurrencyRegistry currencies;
    private final ObjectMapper json;
    private final java.time.Clock clock;

    public LoanController(LoanService loans, LoanServicingService servicing, CurrencyRegistry currencies,
                          ObjectMapper json, java.time.Clock clock) {
        this.clock = clock;
        this.loans = loans;
        this.servicing = servicing;
        this.currencies = currencies;
        this.json = json;
    }

    public record ProposeLoanRequest(@NotNull UUID customerId, @NotNull UUID settlementAccountId,
                                     @NotBlank String principal, @NotBlank String currency,
                                     @NotNull Integer annualRateBps, @NotNull Integer installmentCount) {}

    public record RepaymentRequest(@NotBlank String amount) {}

    /** @param evidence information the decider relied on that the bank does not hold (stored as unverified) */
    public record DecisionRequest(@NotBlank String rationale, java.util.Map<String, String> evidence) {}

    public record RepaymentResponse(UUID id, UUID journalEntryId, long amountMinor, long principalMinor,
                                    long interestMinor, String allocationPolicy, Instant receivedAt) {}

    public record DecisionResponse(String decision, String decidedBy, String deciderType, Instant decidedAt,
                                   String rationale, JsonNode evidence) {}

    public record DelinquencyResponse(LocalDate asOf, long daysPastDue, String bucket, long overduePrincipalMinor,
                                      long overdueInterestMinor, LocalDate oldestUnpaidDueDate) {}

    public record LoanResponse(UUID id, String obligationNumber, String type, String status, UUID creditorPartyId,
                               UUID debtorPartyId, String currency, long principalMinor, int annualRateBps,
                               int installmentCount, String repaymentFrequency, String amortizationMethod,
                               String allocationPolicy, String interestRecognition, UUID positionAccountId,
                               UUID settlementAccountId, LocalDate startDate, LocalDate maturityDate,
                               UUID disbursementEntryId, Instant proposedAt, Instant approvedAt, Instant settledAt,
                               Instant defaultedAt,
                               long contractualOutstandingPrincipalMinor, Long ledgerOutstandingPrincipalMinor,
                               long contractualAccruedUnpaidInterestMinor, Long ledgerInterestReceivableMinor,
                               long scheduledInterestOutstandingMinor, Long dueNowMinor, Long earlySettlementMinor,
                               DelinquencyResponse delinquency, List<LoanView.InstallmentView> schedule,
                               List<RepaymentResponse> repayments, List<DecisionResponse> decisions,
                               List<DelinquencyResponse> delinquencyHistory) {}

    @PostMapping
    public ResponseEntity<LoanResponse> propose(@Valid @RequestBody ProposeLoanRequest req) {
        CurrencyUnit currency = currencies.require(req.currency());
        Obligation o = loans.propose(req.customerId(), req.settlementAccountId(),
                MoneyParser.parsePositive(req.principal(), currency), req.annualRateBps(), req.installmentCount());
        return ResponseEntity.status(HttpStatus.CREATED).body(render(loans.view(o.getId())));
    }

    @GetMapping("/{id}")
    public LoanResponse get(@PathVariable UUID id) {
        return render(loans.view(id));
    }

    @PostMapping("/{id}/approve")
    public LoanResponse approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req) {
        loans.approve(id, req.rationale(), req.evidence() == null ? java.util.Map.of() : req.evidence());
        return render(loans.view(id));
    }

    @PostMapping("/{id}/decline")
    public LoanResponse decline(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req) {
        loans.decline(id, req.rationale(), req.evidence() == null ? java.util.Map.of() : req.evidence());
        return render(loans.view(id));
    }

    @PostMapping("/{id}/cancel")
    public LoanResponse cancel(@PathVariable UUID id) {
        loans.cancel(id);
        return render(loans.view(id));
    }

    @PostMapping("/{id}/disburse")
    public LoanResponse disburse(@PathVariable UUID id) {
        loans.disburse(id);
        return render(loans.view(id));
    }

    @PostMapping("/{id}/repayments")
    public ResponseEntity<LoanResponse> repay(@PathVariable UUID id,
                                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                                              @Valid @RequestBody RepaymentRequest req) {
        CurrencyUnit currency = currencies.require(loans.view(id).obligation().getCurrencyCode());
        LoanService.RepaymentResult result = loans.repay(id, MoneyParser.parsePositive(req.amount(), currency),
                idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(render(loans.view(id)));
    }

    @PostMapping("/{id}/default")
    public LoanResponse declareDefault(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req) {
        loans.declareDefault(id, req.rationale());
        return render(loans.view(id));
    }

    /** End-of-day servicing (accrual + delinquency) for the whole book as of a date (default today). */
    @PostMapping("/servicing")
    public LoanServicingService.RunSummary service(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return servicing.run(asOf == null ? LocalDate.now(clock) : asOf);
    }

    private LoanResponse render(LoanView v) {
        Obligation o = v.obligation();
        var t = v.terms();
        return new LoanResponse(o.getId(), o.getObligationNumber(), o.getType().name(), o.getStatus().name(),
                o.getCreditorPartyId(), o.getDebtorPartyId(), o.getCurrencyCode(), o.getPrincipalMinor(),
                t.getAnnualRateBps(), t.getInstallmentCount(), t.getRepaymentFrequency(), t.getAmortizationMethod(),
                t.getAllocationPolicy(), t.getInterestRecognition().name(), o.getPositionAccountId(),
                o.getSettlementAccountId(), o.getStartDate(), o.getMaturityDate(), o.getDisbursementEntryId(),
                o.getProposedAt(), o.getApprovedAt(), o.getSettledAt(), o.getDefaultedAt(),
                v.contractualOutstandingPrincipalMinor(), v.ledgerOutstandingPrincipalMinor(),
                v.contractualAccruedUnpaidInterestMinor(), v.ledgerInterestReceivableMinor(),
                v.scheduledInterestOutstandingMinor(),
                v.quote() == null ? null : v.quote().dueNowMinor(),
                v.quote() == null ? null : v.quote().payoffMinor(),
                v.delinquency() == null ? null : delinquency(v.delinquency()),
                v.schedule(),
                v.repayments().stream().map(r -> new RepaymentResponse(r.getId(), r.getJournalEntryId(),
                        r.getAmountMinor(), r.getPrincipalMinor(), r.getInterestMinor(), r.getAllocationPolicy(),
                        r.getReceivedAt())).toList(),
                v.decisions().stream().map(d -> new DecisionResponse(d.getDecision().name(), d.getDecidedBy(),
                        d.getDeciderType().name(), d.getDecidedAt(), d.getRationale(), tree(d.getEvidence()))).toList(),
                v.delinquencyHistory().stream().map(e -> new DelinquencyResponse(e.getAsOf(), e.getDaysPastDue(),
                        e.getToBucket().name(), e.getOverduePrincipalMinor(), e.getOverdueInterestMinor(), null))
                        .toList());
    }

    private static DelinquencyResponse delinquency(Delinquency.Status s) {
        return new DelinquencyResponse(s.asOf(), s.daysPastDue(), s.bucket().name(), s.overduePrincipalMinor(),
                s.overdueInterestMinor(), s.oldestUnpaidDueDate());
    }

    private JsonNode tree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
