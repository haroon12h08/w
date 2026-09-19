package com.wbank.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Someone who can hold rights and owe obligations: a legal entity in the world.
 *
 * <p>A party exists independently of the bank. Whether the bank has a relationship with
 * it is a separate fact ({@code customer}); the bank itself is a party (the creditor of
 * its loans). Identity attributes live here; relationship attributes never do.
 *
 * <p>Person and Organization are subtypes (joined tables). PostgreSQL pins the subtype
 * row to the party's type, so a party cannot be both or neither.
 */
@Entity
@Table(name = "party")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "party_type", discriminatorType = DiscriminatorType.STRING)
public abstract class Party {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "display_name")
    private String displayName;

    /** Residence (person) or jurisdiction of incorporation (organization). ISO 3166-1 alpha-2. */
    @Column(name = "country_code", nullable = false, length = 2, columnDefinition = "bpchar")
    private String countryCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Party() {
        // for JPA
    }

    protected Party(UUID id, String legalName, String displayName, String countryCode, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        if (legalName == null || legalName.isBlank()) {
            throw new IllegalArgumentException("A party must have a legal name");
        }
        if (countryCode == null || !countryCode.strip().toUpperCase().matches("^[A-Z]{2}$")) {
            throw new IllegalArgumentException("Country code must be ISO 3166-1 alpha-2: " + countryCode);
        }
        this.legalName = legalName.strip();
        this.displayName = displayName == null || displayName.isBlank() ? null : displayName.strip();
        this.countryCode = countryCode.strip().toUpperCase();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public abstract PartyType type();

    public UUID getId() {
        return id;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCountryCode() {
        return countryCode;
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
