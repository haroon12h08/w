package com.wbank.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.account.funds.FundsReservation;
import com.wbank.account.funds.FundsService;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.payment.domain.Payment;
import com.wbank.payment.domain.PaymentEvent;
import com.wbank.payment.domain.PaymentInstruction;
import com.wbank.payment.domain.PaymentSettlement;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.payment.persistence.PaymentEventRepository;
import com.wbank.payment.persistence.PaymentInstructionRepository;
import com.wbank.payment.persistence.PaymentRepository;
import com.wbank.payment.persistence.PaymentSettlementRepository;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import com.wbank.product.domain.ProductFamily;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of the payment lifecycle. Each public method is exactly one
 * database transaction and one state transition (or none); {@link PaymentService}
 * sequences them.
 *
 * <ul>
 *   <li>{@link #submit}: record the instruction, validate, authorise (reserve funds) or reject.
 *       No ledger effect.</li>
 *   <li>{@link #execute}: consume the reservation and post the settlement journal. The only
 *       step with a financial effect, and it happens at most once.</li>
 *   <li>{@link #cancel}, {@link #expire}, {@link #markFailed}: end an authorisation and
 *       release its hold. No ledger effect.</li>
 *   <li>{@link #reverse}: correct a settled payment with a mirror journal.</li>
 * </ul>
 */
@Service
public class PaymentProcessor {

    public static final String CHANNEL_API = "API";

    private final PaymentInstructionRepository instructions;
    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final PaymentSettlementRepository settlements;
    private final AccountService accounts;
    private final FundsService funds;
    private final LedgerService ledger;
    private final AuditTrail auditTrail;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;
    private final long singlePaymentLimitMajor;
    private final Duration authorizationValidity;

    public PaymentProcessor(PaymentInstructionRepository instructions, PaymentRepository payments,
                            PaymentEventRepository events, PaymentSettlementRepository settlements,
                            AccountService accounts, FundsService funds, LedgerService ledger, AuditTrail auditTrail,
                            JdbcTemplate jdbc, ObjectMapper json, Clock clock,
                            @Value("${wbank.payments.single-payment-limit-major:1000000}") long singlePaymentLimitMajor,
                            @Value("${wbank.payments.authorization-validity:P7D}") Duration authorizationValidity) {
        this.instructions = instructions;
        this.payments = payments;
        this.events = events;
        this.settlements = settlements;
        this.accounts = accounts;
        this.funds = funds;
        this.ledger = ledger;
        this.auditTrail = auditTrail;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.singlePaymentLimitMajor = singlePaymentLimitMajor;
        this.authorizationValidity = authorizationValidity;
    }

    public record SubmitCommand(String idempotencyKey, UUID debtorAccountId, UUID creditorAccountId, Money amount,
                                String remittanceInfo) {}

    public record Submission(Payment payment, boolean replayed) {}

    /** One named check, its outcome, and the evidence it looked at. */
    record Check(String name, boolean passed, String reasonIfFailed, Map<String, Object> evidence) {
        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", name);
            m.put("passed", passed);
            m.putAll(evidence);
            return m;
        }
    }

    // ------------------------------------------------------------------ submit

    @Transactional
    public Submission submit(SubmitCommand cmd, OperationContext context) {
        String key = cmd.idempotencyKey() == null ? null : cmd.idempotencyKey().strip();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("A payment requires an idempotency key");
        }
        // Serialise every submission carrying this key; released at COMMIT/ROLLBACK.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 7))::text", String.class,
                "payment:" + key);
        var existing = instructions.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            PaymentInstruction prior = existing.get();
            String fingerprint = PaymentInstruction.fingerprint(cmd.debtorAccountId(), cmd.creditorAccountId(),
                    cmd.amount(), cmd.remittanceInfo());
            if (!prior.getRequestFingerprint().equals(fingerprint)) {
                throw new ConflictException("payment.idempotency_key_reused",
                        "Idempotency key %s was already used for a different payment instruction".formatted(key));
            }
            return new Submission(payments.findByInstructionId(prior.getId()).orElseThrow(), true);
        }

        // Malformed requests are refused without a record; they are not instructions.
        Account debtor = accounts.require(cmd.debtorAccountId());
        Account creditor = accounts.require(cmd.creditorAccountId());
        Instant now = clock.instant();
        PaymentInstruction instruction = instructions.save(PaymentInstruction.receive(key, debtor.getId(),
                creditor.getId(), cmd.amount(), cmd.remittanceInfo(), CHANNEL_API, context, now));
        UUID paymentId = UUID.randomUUID();
        List<PaymentEvent> trail = new ArrayList<>();
        trail.add(event(paymentId, trail, "INSTRUCTION_RECEIVED", null, null, context, Map.of(
                "instructionId", instruction.getId().toString(),
                "debtorAccountId", debtor.getId().toString(),
                "creditorAccountId", creditor.getId().toString(),
                "amountMinor", cmd.amount().minorUnits(),
                "currency", cmd.amount().currency().code(),
                "initiatedBy", context.actor(),
                "initiatorType", context.actorType().name(),
                "channel", CHANNEL_API), now));

        // 1. Validation: is this a payment the bank can process at all?
        List<Check> validation = validate(debtor, creditor, cmd.amount());
        Check failedValidation = validation.stream().filter(c -> !c.passed()).findFirst().orElse(null);
        if (failedValidation != null) {
            return reject(paymentId, instruction, trail, "VALIDATION_FAILED", validation, failedValidation, context, now);
        }
        trail.add(event(paymentId, trail, "VALIDATION_PASSED", null, null, context,
                Map.of("checks", validation.stream().map(Check::asMap).toList()), now));

        // 2. Authorisation: is the bank permitted to commit these funds? Decided under the
        //    debtor's balance lock, so concurrent authorisations cannot share the same money.
        FundsService.Balances before = funds.lockedBalances(debtor);
        List<Check> authorization = authorize(debtor, cmd.amount(), before);
        Check failedAuth = authorization.stream().filter(c -> !c.passed()).findFirst().orElse(null);
        if (failedAuth != null) {
            return reject(paymentId, instruction, trail, "AUTHORIZATION_DENIED", authorization, failedAuth, context, now);
        }
        Instant expiresAt = now.plus(authorizationValidity);
        FundsReservation hold = funds.reserve(debtor, cmd.amount(), "PAYMENT", paymentId, expiresAt);
        Payment payment = payments.save(Payment.authorized(paymentId, instruction.getId(), hold.getId(), now, expiresAt));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("checks", authorization.stream().map(Check::asMap).toList());
        details.put("reservationId", hold.getId().toString());
        details.put("availableBeforeMinor", before.availableMinor());
        details.put("availableAfterMinor", before.availableMinor() - cmd.amount().minorUnits());
        details.put("expiresAt", expiresAt.toString());
        trail.add(event(paymentId, trail, "AUTHORIZED", null, PaymentStatus.AUTHORIZED, context, details, now));
        events.saveAll(trail);
        audit("payment.authorized", payment, instruction, context);
        return new Submission(payment, false);
    }

    private List<Check> validate(Account debtor, Account creditor, Money amount) {
        String ccy = amount.currency().code();
        return List.of(
                check("DEBTOR_IS_DEPOSIT_ACCOUNT", debtor.getProductFamily() == ProductFamily.DEPOSIT,
                        "NOT_A_DEPOSIT_ACCOUNT", Map.of("productFamily", debtor.getProductFamily().name())),
                check("CREDITOR_IS_DEPOSIT_ACCOUNT", creditor.getProductFamily() == ProductFamily.DEPOSIT,
                        "NOT_A_DEPOSIT_ACCOUNT", Map.of("productFamily", creditor.getProductFamily().name())),
                check("DEBTOR_ACCOUNT_ACTIVE", debtor.isOperable(), "DEBTOR_ACCOUNT_NOT_ACTIVE",
                        Map.of("status", debtor.getStatus().name())),
                check("CREDITOR_ACCOUNT_ACTIVE", creditor.isOperable(), "CREDITOR_ACCOUNT_NOT_ACTIVE",
                        Map.of("status", creditor.getStatus().name())),
                check("CURRENCY_MATCHES_DEBTOR", debtor.getCurrencyCode().equals(ccy), "CURRENCY_MISMATCH",
                        Map.of("accountCurrency", debtor.getCurrencyCode(), "paymentCurrency", ccy)),
                check("CURRENCY_MATCHES_CREDITOR", creditor.getCurrencyCode().equals(ccy), "CURRENCY_MISMATCH",
                        Map.of("accountCurrency", creditor.getCurrencyCode(), "paymentCurrency", ccy)));
    }

    private List<Check> authorize(Account debtor, Money amount, FundsService.Balances balances) {
        CurrencyUnit currency = amount.currency();
        long limitMinor = BigDecimal.valueOf(singlePaymentLimitMajor).movePointRight(currency.minorUnit()).longValueExact();
        long available = balances.availableMinor() == null ? Long.MAX_VALUE : balances.availableMinor();
        Map<String, Object> fundsEvidence = new LinkedHashMap<>();
        fundsEvidence.put("ledgerBalanceMinor", balances.ledgerBalanceMinor());
        fundsEvidence.put("reservedMinor", balances.reservedMinor());
        fundsEvidence.put("floorMinor", balances.floorMinor());
        fundsEvidence.put("availableMinor", balances.availableMinor());
        fundsEvidence.put("requestedMinor", amount.minorUnits());
        return List.of(
                check("WITHIN_SINGLE_PAYMENT_LIMIT", amount.minorUnits() <= limitMinor, "LIMIT_EXCEEDED",
                        Map.of("limitMinor", limitMinor, "requestedMinor", amount.minorUnits())),
                check("FUNDS_AVAILABLE", available >= amount.minorUnits(), "INSUFFICIENT_FUNDS", fundsEvidence));
    }

    private Submission reject(UUID paymentId, PaymentInstruction instruction, List<PaymentEvent> trail, String eventType,
                              List<Check> checks, Check failed, OperationContext context, Instant now) {
        Payment payment = payments.save(Payment.rejected(paymentId, instruction.getId(), failed.reasonIfFailed(),
                "Check " + failed.name() + " failed", now));
        trail.add(event(paymentId, trail, eventType, null, PaymentStatus.REJECTED, context, Map.of(
                "reasonCode", failed.reasonIfFailed(),
                "failedCheck", failed.name(),
                "checks", checks.stream().map(Check::asMap).toList()), now));
        events.saveAll(trail);
        audit("payment.rejected", payment, instruction, context);
        return new Submission(payment, false);
    }

    // ----------------------------------------------------------------- execute

    /** Settles an authorised payment. Returns the payment in its resulting state. */
    @Transactional
    public Payment execute(UUID paymentId, OperationContext context) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() == PaymentStatus.SETTLED || payment.getStatus() == PaymentStatus.REVERSED) {
            return payment; // already settled: never a second financial effect
        }
        InvalidStateTransitionException.require(payment.getStatus() == PaymentStatus.AUTHORIZED, "payment", paymentId,
                payment.getStatus(), PaymentStatus.SETTLED);
        Instant now = clock.instant();
        if (payment.isExpiredAt(now)) {
            return expireLocked(payment, context, now);
        }
        PaymentInstruction instruction = instructions.findById(payment.getInstructionId()).orElseThrow();
        Account debtor = accounts.require(instruction.getDebtorAccountId());
        Account creditor = accounts.require(instruction.getCreditorAccountId());
        // Account state may have changed since authorisation (freeze, close). Re-check.
        if (!debtor.isOperable() || !creditor.isOperable()) {
            return failLocked(payment, "ACCOUNT_NOT_OPERABLE", "debtor=%s creditor=%s"
                    .formatted(debtor.getStatus(), creditor.getStatus()), context, now);
        }

        funds.consume(payment.getReservationId());
        Money amount = Money.ofMinorUnits(instruction.getAmountMinor(),
                ledgerCurrency(instruction));
        PostedEntry posted = ledger.post(new JournalEntryRequest(
                JournalEntryType.PAYMENT_TRANSFER,
                instruction.getRemittanceInfo() != null ? instruction.getRemittanceInfo()
                        : "Payment " + paymentId,
                LocalDate.now(clock), amount.currency(),
                List.of(PostingInstruction.debit(debtor.getLedgerAccountId(), amount),
                        PostingInstruction.credit(creditor.getLedgerAccountId(), amount)),
                "payment.settlement:" + paymentId, context));
        settlements.save(PaymentSettlement.internal(paymentId, posted.entry().getId(), now));
        payment.settle(now);
        payments.save(payment);
        events.save(nextEvent(payment, "SETTLED", PaymentStatus.AUTHORIZED, PaymentStatus.SETTLED, context, Map.of(
                "journalEntryId", posted.entry().getId().toString(),
                "entryNumber", posted.entry().getEntryNumber() == null ? -1 : posted.entry().getEntryNumber(),
                "settlementMethod", PaymentSettlement.INTERNAL_BOOK_TRANSFER,
                "reservationConsumed", payment.getReservationId().toString(),
                "postings", posted.postings().stream().map(p -> Map.of(
                        "ledgerAccountId", p.getLedgerAccountId().toString(),
                        "direction", p.getDirection().name(),
                        "amountMinor", p.getAmountMinor(),
                        "balanceAfterMinor", p.getBalanceAfterMinor())).toList()), now));
        audit("payment.settled", payment, instruction, context);
        return payment;
    }

    // --------------------------------------------------- endings without money

    @Transactional
    public Payment cancel(UUID paymentId, String reason, OperationContext context) {
        Payment payment = lock(paymentId);
        PaymentStatus from = payment.getStatus();
        Instant now = clock.instant();
        payment.cancel(reason, now);
        funds.release(payment.getReservationId());
        payments.save(payment);
        events.save(nextEvent(payment, "CANCELLED", from, PaymentStatus.CANCELLED, context,
                Map.of("reason", reason == null ? "" : reason, "reservationReleased", payment.getReservationId().toString()),
                now));
        audit("payment.cancelled", payment, null, context);
        return payment;
    }

    /** Expires the payment if its authorisation lapsed by {@code asOf}; otherwise no change. */
    @Transactional
    public Payment expire(UUID paymentId, Instant asOf, OperationContext context) {
        Payment payment = lock(paymentId);
        return payment.isExpiredAt(asOf) ? expireLocked(payment, context, clock.instant()) : payment;
    }

    /** Records that execution failed. Only an AUTHORIZED payment can fail; the hold is released. */
    @Transactional
    public Payment markFailed(UUID paymentId, String code, String detail, OperationContext context) {
        Payment payment = lock(paymentId);
        if (payment.getStatus() != PaymentStatus.AUTHORIZED) {
            return payment;
        }
        return failLocked(payment, code, detail, context, clock.instant());
    }

    private Payment expireLocked(Payment payment, OperationContext context, Instant now) {
        payment.expire(now);
        funds.release(payment.getReservationId());
        payments.save(payment);
        events.save(nextEvent(payment, "EXPIRED", PaymentStatus.AUTHORIZED, PaymentStatus.EXPIRED, context,
                Map.of("expiresAt", payment.getExpiresAt().toString(),
                        "reservationReleased", payment.getReservationId().toString()), now));
        audit("payment.expired", payment, null, context);
        return payment;
    }

    private Payment failLocked(Payment payment, String code, String detail, OperationContext context, Instant now) {
        payment.fail(code, truncate(detail), now);
        funds.release(payment.getReservationId());
        payments.save(payment);
        events.save(nextEvent(payment, "EXECUTION_FAILED", PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, context,
                Map.of("reasonCode", code, "detail", truncate(detail),
                        "reservationReleased", payment.getReservationId().toString(),
                        "ledgerEffect", "none"), now));
        audit("payment.failed", payment, null, context);
        return payment;
    }

    // ----------------------------------------------------------------- reverse

    /**
     * Corrects a settled payment by posting the exact mirror of its settlement entry. The
     * original settlement remains; both are visible. The creditor must still have the
     * funds available: a reversal cannot spend money held for something else, nor push
     * an account into an unauthorised overdraft.
     */
    @Transactional
    public Payment reverse(UUID paymentId, String reason, OperationContext context) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reversal requires a reason");
        }
        Payment payment = lock(paymentId);
        InvalidStateTransitionException.require(payment.getStatus() == PaymentStatus.SETTLED, "payment", paymentId,
                payment.getStatus(), PaymentStatus.REVERSED);
        PaymentInstruction instruction = instructions.findById(payment.getInstructionId()).orElseThrow();
        PaymentSettlement settlement = settlements.findByPaymentId(paymentId).orElseThrow();
        Account creditor = accounts.require(instruction.getCreditorAccountId());
        funds.requireAvailable(creditor, Money.ofMinorUnits(instruction.getAmountMinor(), ledgerCurrency(instruction)));

        PostedEntry reversal = ledger.reverse(settlement.getJournalEntryId(), reason.strip(), context);
        Instant now = clock.instant();
        payment.reverse(reversal.entry().getId(), reason.strip(), now);
        payments.save(payment);
        events.save(nextEvent(payment, "REVERSED", PaymentStatus.SETTLED, PaymentStatus.REVERSED, context, Map.of(
                "reason", reason.strip(),
                "reversalEntryId", reversal.entry().getId().toString(),
                "reversesEntryId", settlement.getJournalEntryId().toString()), now));
        audit("payment.reversed", payment, instruction, context);
        return payment;
    }

    // --------------------------------------------------------------- internals

    private Payment lock(UUID paymentId) {
        return payments.findByIdForUpdate(paymentId).orElseThrow(() -> NotFoundException.of("payment", paymentId));
    }

    private CurrencyUnit ledgerCurrency(PaymentInstruction instruction) {
        return funds.currencyOf(instruction.getCurrencyCode());
    }

    private static Check check(String name, boolean passed, String reason, Map<String, Object> evidence) {
        return new Check(name, passed, reason, evidence);
    }

    private PaymentEvent event(UUID paymentId, List<PaymentEvent> trail, String type, PaymentStatus from,
                               PaymentStatus to, OperationContext context, Map<String, ?> details, Instant now) {
        return PaymentEvent.of(paymentId, trail.size() + 1, type, from, to, context, toJson(details), now);
    }

    private PaymentEvent nextEvent(Payment payment, String type, PaymentStatus from, PaymentStatus to,
                                   OperationContext context, Map<String, ?> details, Instant now) {
        int seq = (int) events.countByPaymentId(payment.getId()) + 1;
        return PaymentEvent.of(payment.getId(), seq, type, from, to, context, toJson(details), now);
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Payment event details could not be serialised", e);
        }
    }

    private void audit(String event, Payment payment, PaymentInstruction instruction, OperationContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", payment.getStatus().name());
        payload.put("instructionId", payment.getInstructionId().toString());
        if (payment.getReasonCode() != null) {
            payload.put("reasonCode", payment.getReasonCode());
        }
        if (instruction != null) {
            payload.put("amountMinor", instruction.getAmountMinor());
            payload.put("currency", instruction.getCurrencyCode());
        }
        auditTrail.record(event, "payment", payment.getId(), context, payload);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
