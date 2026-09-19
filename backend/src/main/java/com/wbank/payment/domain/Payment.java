package com.wbank.payment.domain;

import com.wbank.platform.error.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Where the processing of one instruction stands. This is the only mutable payment
 * record, and it changes only through lifecycle transitions; the reasons and evidence for
 * each transition are appended to {@link PaymentEvent}.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "instruction_id", nullable = false, updatable = false)
    private UUID instructionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reason_detail")
    private String reasonDetail;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "authorized_at")
    private Instant authorizedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_entry_id")
    private UUID reversalEntryId;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payment() {
        // for JPA
    }

    public static Payment rejected(UUID id, UUID instructionId, String reasonCode, String detail, Instant now) {
        Payment p = base(id, instructionId, now);
        p.status = PaymentStatus.REJECTED;
        p.reasonCode = Objects.requireNonNull(reasonCode);
        p.reasonDetail = detail;
        p.closedAt = now;
        return p;
    }

    public static Payment authorized(UUID id, UUID instructionId, UUID reservationId, Instant now, Instant expiresAt) {
        Payment p = base(id, instructionId, now);
        p.status = PaymentStatus.AUTHORIZED;
        p.reservationId = Objects.requireNonNull(reservationId);
        p.authorizedAt = now;
        p.expiresAt = expiresAt;
        return p;
    }

    private static Payment base(UUID id, UUID instructionId, Instant now) {
        Payment p = new Payment();
        p.id = id;
        p.instructionId = instructionId;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    public void settle(Instant now) {
        transition(PaymentStatus.SETTLED, now);
        this.settledAt = now;
    }

    public void cancel(String reason, Instant now) {
        close(PaymentStatus.CANCELLED, "CANCELLED_BY_INITIATOR", reason, now);
    }

    public void expire(Instant now) {
        close(PaymentStatus.EXPIRED, "AUTHORIZATION_EXPIRED", "Not executed before " + expiresAt, now);
    }

    public void fail(String code, String detail, Instant now) {
        close(PaymentStatus.FAILED, code, detail, now);
    }

    public void reverse(UUID reversalEntryId, String reason, Instant now) {
        transition(PaymentStatus.REVERSED, now);
        this.reversalEntryId = Objects.requireNonNull(reversalEntryId);
        this.reasonCode = "REVERSED";
        this.reasonDetail = reason;
        this.reversedAt = now;
        this.closedAt = now;
    }

    private void close(PaymentStatus target, String code, String detail, Instant now) {
        transition(target, now);
        this.reasonCode = code;
        this.reasonDetail = detail;
        this.closedAt = now;
    }

    private void transition(PaymentStatus target, Instant now) {
        InvalidStateTransitionException.require(status.canTransitionTo(target), "payment", id, status, target);
        this.status = target;
        this.updatedAt = now;
    }

    public boolean isExpiredAt(Instant now) {
        return status == PaymentStatus.AUTHORIZED && !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Instant getAuthorizedAt() {
        return authorizedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public UUID getReversalEntryId() {
        return reversalEntryId;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
