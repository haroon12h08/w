package com.wbank.payment.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.funds.FundsService;
import com.wbank.ledger.api.PostedEntryResponse.PostingResponse;
import com.wbank.payment.PaymentProcessor;
import com.wbank.payment.PaymentService;
import com.wbank.payment.PaymentView;
import com.wbank.payment.domain.PaymentEvent;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Payment instructions and their lifecycle. There is no way to create a ledger entry from
 * here other than by settling an authorised instruction.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentService payments;
    private final FundsService funds;
    private final CurrencyRegistry currencies;
    private final ObjectMapper json;

    public PaymentController(PaymentService payments, FundsService funds, CurrencyRegistry currencies, ObjectMapper json) {
        this.payments = payments;
        this.funds = funds;
        this.currencies = currencies;
        this.json = json;
    }

    public record SubmitPaymentRequest(@NotNull UUID debtorAccountId, @NotNull UUID creditorAccountId,
                                       @NotBlank String amount, @NotBlank String currency,
                                       String remittanceInformation, Boolean executeImmediately) {}

    public record ReasonRequest(@NotBlank String reason) {}

    public record EventResponse(int sequence, String type, String fromStatus, String toStatus, Instant occurredAt,
                                String actor, String actorType, UUID correlationId, JsonNode details) {}

    public record PaymentResponse(UUID id, String status, String reasonCode, String reasonDetail, boolean replayed,
                                  Map<String, Object> instruction, Map<String, Object> reservation,
                                  Map<String, Object> settlement, List<PostingResponse> settlementPostings,
                                  UUID reversalEntryId, List<PostingResponse> reversalPostings,
                                  Instant authorizedAt, Instant expiresAt, Instant settledAt, Instant reversedAt,
                                  List<EventResponse> events) {}

    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> submit(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody SubmitPaymentRequest req) {
        var currency = currencies.require(req.currency());
        PaymentView v = payments.submit(new PaymentProcessor.SubmitCommand(key, req.debtorAccountId(),
                        req.creditorAccountId(), MoneyParser.parsePositive(req.amount(), currency),
                        req.remittanceInformation()),
                req.executeImmediately() == null || req.executeImmediately());
        return ResponseEntity.status(v.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(render(v));
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@PathVariable UUID id) {
        return render(payments.view(id, false));
    }

    @PostMapping("/payments/{id}/execute")
    public PaymentResponse execute(@PathVariable UUID id) {
        return render(payments.execute(id));
    }

    @PostMapping("/payments/{id}/cancel")
    public PaymentResponse cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return render(payments.cancel(id, req.reason()));
    }

    @PostMapping("/payments/{id}/reversal")
    public PaymentResponse reverse(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return render(payments.reverse(id, req.reason()));
    }

    /** Operational sweep: expire authorisations that lapsed by {@code asOf} (default now). */
    @PostMapping("/payments/expirations")
    public Map<String, Object> expire(@RequestParam(required = false) Instant asOf) {
        Instant at = asOf == null ? Instant.now() : asOf;
        return Map.of("asOf", at, "expired", payments.expireDue(at));
    }

    @GetMapping("/accounts/{id}/balances")
    public FundsService.Balances balances(@PathVariable UUID id) {
        return funds.balances(id);
    }

    private PaymentResponse render(PaymentView v) {
        var i = v.instruction();
        var p = v.payment();
        Map<String, Object> instruction = new java.util.LinkedHashMap<>();
        instruction.put("id", i.getId());
        instruction.put("idempotencyKey", i.getIdempotencyKey());
        instruction.put("debtorAccountId", i.getDebtorAccountId());
        instruction.put("creditorAccountId", i.getCreditorAccountId());
        instruction.put("amountMinor", i.getAmountMinor());
        instruction.put("currency", i.getCurrencyCode());
        instruction.put("remittanceInformation", i.getRemittanceInfo());
        instruction.put("receivedAt", i.getReceivedAt());
        instruction.put("initiatedBy", i.getInitiatedBy());
        instruction.put("initiatorType", i.getInitiatorType());
        instruction.put("correlationId", i.getCorrelationId());
        instruction.put("channel", i.getChannel());
        Map<String, Object> reservation = v.reservation() == null ? null : Map.of(
                "id", v.reservation().getId(), "status", v.reservation().getStatus().name(),
                "amountMinor", v.reservation().getAmountMinor(), "expiresAt", v.reservation().getExpiresAt());
        Map<String, Object> settlement = v.settlement() == null ? null : Map.of(
                "journalEntryId", v.settlement().getJournalEntryId(), "method", v.settlement().getMethod(),
                "settledAt", v.settlement().getSettledAt());
        return new PaymentResponse(p.getId(), p.getStatus().name(), p.getReasonCode(), p.getReasonDetail(),
                v.replayed(), instruction, reservation, settlement,
                v.settlementPostings().stream().map(PostingResponse::from).toList(), p.getReversalEntryId(),
                v.reversalPostings().stream().map(PostingResponse::from).toList(), p.getAuthorizedAt(),
                p.getExpiresAt(), p.getSettledAt(), p.getReversedAt(),
                v.events().stream().map(this::render).toList());
    }

    private EventResponse render(PaymentEvent e) {
        try {
            return new EventResponse(e.getSequenceNo(), e.getEventType(),
                    e.getFromStatus() == null ? null : e.getFromStatus().name(),
                    e.getToStatus() == null ? null : e.getToStatus().name(), e.getOccurredAt(), e.getActor(),
                    e.getActorType().name(), e.getCorrelationId(), json.readTree(e.getDetails()));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
