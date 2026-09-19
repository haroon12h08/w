package com.wbank.payment;

import com.wbank.ledger.LedgerService;
import com.wbank.ledger.domain.Posting;
import com.wbank.account.funds.FundsService;
import com.wbank.payment.domain.Payment;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.payment.persistence.PaymentEventRepository;
import com.wbank.payment.persistence.PaymentInstructionRepository;
import com.wbank.payment.persistence.PaymentRepository;
import com.wbank.payment.persistence.PaymentSettlementRepository;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.DomainException;
import com.wbank.platform.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sequences the payment lifecycle. Deliberately <b>not</b> transactional itself: each step
 * runs in its own transaction ({@link PaymentProcessor}), so that
 *
 * <ul>
 *   <li>an authorisation is durable before execution is attempted, and</li>
 *   <li>if execution throws (its transaction rolls back completely, so no partial financial
 *       effect exists), the failure can still be <em>recorded</em>, in a fresh transaction, as
 *       FAILED with the hold released.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentProcessor processor;
    private final PaymentRepository payments;
    private final PaymentInstructionRepository instructions;
    private final PaymentEventRepository events;
    private final PaymentSettlementRepository settlements;
    private final FundsService funds;
    private final LedgerService ledger;

    public PaymentService(PaymentProcessor processor, PaymentRepository payments,
                          PaymentInstructionRepository instructions, PaymentEventRepository events,
                          PaymentSettlementRepository settlements, FundsService funds, LedgerService ledger) {
        this.processor = processor;
        this.payments = payments;
        this.instructions = instructions;
        this.events = events;
        this.settlements = settlements;
        this.funds = funds;
        this.ledger = ledger;
    }

    /**
     * Records, validates and authorises an instruction; if {@code executeImmediately} and
     * authorised, settles it straight away (in a second transaction).
     */
    public PaymentView submit(PaymentProcessor.SubmitCommand cmd, boolean executeImmediately) {
        OperationContext context = RequestContext.forOperation("payment.submit");
        PaymentProcessor.Submission s = processor.submit(cmd, context);
        if (!s.replayed() && executeImmediately && s.payment().getStatus() == PaymentStatus.AUTHORIZED) {
            executeSafely(s.payment().getId(), context.withOperation("payment.execute"));
        }
        return view(s.payment().getId(), s.replayed());
    }

    public PaymentView execute(UUID paymentId) {
        executeSafely(paymentId, RequestContext.forOperation("payment.execute"));
        return view(paymentId, false);
    }

    public PaymentView cancel(UUID paymentId, String reason) {
        processor.cancel(paymentId, reason, RequestContext.forOperation("payment.cancel"));
        return view(paymentId, false);
    }

    public PaymentView reverse(UUID paymentId, String reason) {
        processor.reverse(paymentId, reason, RequestContext.forOperation("payment.reverse"));
        return view(paymentId, false);
    }

    /** Expires every authorisation that lapsed by {@code asOf}. Returns how many expired. */
    public int expireDue(Instant asOf) {
        OperationContext context = RequestContext.forOperation("payment.expire");
        int n = 0;
        for (UUID id : payments.findAuthorizedExpiringBy(asOf)) {
            if (processor.expire(id, asOf, context).getStatus() == PaymentStatus.EXPIRED) {
                n++;
            }
        }
        return n;
    }

    private void executeSafely(UUID paymentId, OperationContext context) {
        try {
            processor.execute(paymentId, context);
        } catch (DomainException e) {
            // A lifecycle/business refusal of the request itself (e.g. not AUTHORIZED): the
            // payment is unchanged and the caller should know why.
            if (e instanceof NotFoundException || e.errorCode().endsWith("invalid_transition")) {
                throw e;
            }
            recordFailure(paymentId, e.errorCode(), e.getMessage(), context);
        } catch (RuntimeException e) {
            recordFailure(paymentId, "PROCESSING_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage(), context);
        }
    }

    private void recordFailure(UUID paymentId, String code, String detail, OperationContext context) {
        // The execution transaction rolled back in full: no posting, no settlement, the hold
        // still active. Record the failure and release the hold in a new transaction.
        log.warn("Payment {} execution failed ({}): {}", paymentId, code, detail);
        processor.markFailed(paymentId, code, detail, context);
    }

    @Transactional(readOnly = true)
    public PaymentView view(UUID paymentId, boolean replayed) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> NotFoundException.of("payment", paymentId));
        var instruction = instructions.findById(payment.getInstructionId()).orElseThrow();
        var settlement = settlements.findByPaymentId(paymentId).orElse(null);
        List<Posting> settlementPostings = settlement == null ? List.of() : ledger.postingsOf(settlement.getJournalEntryId());
        List<Posting> reversalPostings = payment.getReversalEntryId() == null ? List.of()
                : ledger.postingsOf(payment.getReversalEntryId());
        return new PaymentView(instruction, payment, events.findByPaymentIdOrderBySequenceNoAsc(paymentId),
                payment.getReservationId() == null ? null : funds.require(payment.getReservationId()),
                settlement, settlementPostings, reversalPostings, replayed);
    }
}
