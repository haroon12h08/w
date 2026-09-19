package com.wbank.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A legal person that is not a natural person. */
@Entity
@Table(name = "organization")
@DiscriminatorValue("ORGANIZATION")
@PrimaryKeyJoinColumn(name = "party_id")
public class Organization extends Party {

    /** Registry identifier. Null only for parties migrated from the pre-V7 customer table. */
    @Column(name = "registration_number", updatable = false)
    private String registrationNumber;

    protected Organization() {
        // for JPA
    }

    public static Organization register(UUID id, String legalName, String displayName, String registrationNumber,
                                        String countryCode, Instant now) {
        if (registrationNumber == null || registrationNumber.isBlank()) {
            throw new IllegalArgumentException("An organization needs a registration number");
        }
        Organization o = new Organization(id, legalName, displayName, countryCode, now);
        o.registrationNumber = registrationNumber.strip();
        return o;
    }

    private Organization(UUID id, String legalName, String displayName, String countryCode, Instant now) {
        super(id, legalName, displayName, countryCode, now);
    }

    @Override
    public PartyType type() {
        return PartyType.ORGANIZATION;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }
}
