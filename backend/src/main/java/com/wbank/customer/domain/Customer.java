package com.wbank.customer.domain;

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
 * The institution's relationship with exactly one {@link com.wbank.party.domain.Party}.
 *
 * <p>Who someone <em>is</em> (name, type, jurisdiction) belongs to the party. That the
 * bank has agreed to do business with them, since when, and whether it still does, belongs
 * here. Keeping the two apart means a party can exist without being a customer (the bank
 * itself, a loan counterparty, a payee), and a closed relationship never erases the party.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Human-facing, immutable, unique. Generated from a database sequence. */
    @Column(name = "customer_number", nullable = false, updatable = false)
    private String customerNumber;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    /** Contact channel of this relationship (not an identity attribute of the party). */
    @Column(name = "email")
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CustomerStatus status;

    /** When the relationship was opened (proposed). */
    @Column(name = "onboarded_at", nullable = false, updatable = false)
    private Instant onboardedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Customer() {
        // for JPA
    }

    public static Customer open(UUID id, String customerNumber, UUID partyId, String email, Instant now) {
        Customer c = new Customer();
        c.id = Objects.requireNonNull(id);
        c.customerNumber = Objects.requireNonNull(customerNumber);
        c.partyId = Objects.requireNonNull(partyId, "a customer relationship must name its party");
        c.email = email == null || email.isBlank() ? null : email.strip().toLowerCase();
        c.status = CustomerStatus.PENDING;
        c.onboardedAt = now;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    public void activate(Instant now) {
        transition(CustomerStatus.ACTIVE, now);
        this.activatedAt = now;
    }

    public void suspend(Instant now) {
        transition(CustomerStatus.SUSPENDED, now);
    }

    public void reactivate(Instant now) {
        if (status != CustomerStatus.SUSPENDED) {
            throw new InvalidStateTransitionException("customer", id, status, CustomerStatus.ACTIVE);
        }
        transition(CustomerStatus.ACTIVE, now);
    }

    public void close(Instant now) {
        transition(CustomerStatus.CLOSED, now);
        this.closedAt = now;
    }

    private void transition(CustomerStatus target, Instant now) {
        InvalidStateTransitionException.require(status.canTransitionTo(target), "customer", id, status, target);
        this.status = target;
        this.updatedAt = now;
    }

    /** Whether new business (accounts, obligations) may start under this relationship. */
    public boolean canStartNewBusiness() {
        return status == CustomerStatus.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getCustomerNumber() {
        return customerNumber;
    }

    public UUID getPartyId() {
        return partyId;
    }

    public String getEmail() {
        return email;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public Instant getOnboardedAt() {
        return onboardedAt;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
