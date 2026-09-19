package com.wbank.payment.domain;

import com.wbank.platform.context.ActorType;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * What the initiator asked for, exactly as received, with who asked and when. Never
 * changes: whatever later happens to the payment, the request itself is evidence.
 */
@Entity
@Immutable
@Table(name = "payment_instruction")
public class PaymentInstruction {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "debtor_account_id", nullable = false, updatable = false)
    private UUID debtorAccountId;

    @Column(name = "creditor_account_id", nullable = false, updatable = false)
    private UUID creditorAccountId;

    @Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "bpchar")
    private String currencyCode;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "remittance_info", updatable = false)
    private String remittanceInfo;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "initiated_by", nullable = false, updatable = false)
    private String initiatedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiator_type", nullable = false, updatable = false)
    private ActorType initiatorType;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

    protected PaymentInstruction() {
        // for JPA
    }

    public static PaymentInstruction receive(String idempotencyKey, UUID debtorAccountId, UUID creditorAccountId,
                                             Money amount, String remittanceInfo, String channel,
                                             OperationContext context, Instant now) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.strip().length() > 200) {
            throw new IllegalArgumentException("A payment requires an idempotency key of 1..200 characters");
        }
        if (debtorAccountId.equals(creditorAccountId)) {
            throw new IllegalArgumentException("Debtor and creditor accounts must differ");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        PaymentInstruction i = new PaymentInstruction();
        i.id = UUID.randomUUID();
        i.idempotencyKey = idempotencyKey.strip();
        i.debtorAccountId = debtorAccountId;
        i.creditorAccountId = creditorAccountId;
        i.currencyCode = amount.currency().code();
        i.amountMinor = amount.minorUnits();
        i.remittanceInfo = remittanceInfo == null || remittanceInfo.isBlank() ? null : remittanceInfo.strip();
        i.requestFingerprint = fingerprint(debtorAccountId, creditorAccountId, amount, i.remittanceInfo);
        i.receivedAt = now;
        i.initiatedBy = context.actor();
        i.initiatorType = context.actorType();
        i.correlationId = context.correlationId();
        i.channel = channel;
        return i;
    }

    /** SHA-256 over what the payment means financially; provenance is excluded (a retry may differ). */
    public static String fingerprint(UUID debtor, UUID creditor, Money amount, String remittanceInfo) {
        String r = remittanceInfo == null ? "" : remittanceInfo.strip();
        String canonical = "v1|" + debtor + "|" + creditor + "|" + amount.currency().code() + "|"
                + amount.minorUnits() + "|" + r.length() + ":" + r;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public UUID getDebtorAccountId() {
        return debtorAccountId;
    }

    public UUID getCreditorAccountId() {
        return creditorAccountId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getRemittanceInfo() {
        return remittanceInfo;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public ActorType getInitiatorType() {
        return initiatorType;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getChannel() {
        return channel;
    }
}
