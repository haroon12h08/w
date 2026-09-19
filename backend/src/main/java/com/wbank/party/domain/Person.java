package com.wbank.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A natural person. */
@Entity
@Table(name = "person")
@DiscriminatorValue("PERSON")
@PrimaryKeyJoinColumn(name = "party_id")
public class Person extends Party {

    @Column(name = "given_name")
    private String givenName;

    @Column(name = "family_name")
    private String familyName;

    /** Null only for parties migrated from the pre-V7 customer table. Required for new persons. */
    @Column(name = "date_of_birth", updatable = false)
    private LocalDate dateOfBirth;

    protected Person() {
        // for JPA
    }

    public static Person register(UUID id, String givenName, String familyName, LocalDate dateOfBirth,
                                  String countryCode, Instant now) {
        if (givenName == null || givenName.isBlank() || familyName == null || familyName.isBlank()) {
            throw new IllegalArgumentException("A person needs a given name and a family name");
        }
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("A person needs a date of birth");
        }
        if (!dateOfBirth.isBefore(LocalDate.ofInstant(now, java.time.ZoneOffset.UTC))) {
            throw new IllegalArgumentException("Date of birth must be in the past: " + dateOfBirth);
        }
        Person p = new Person(id, givenName.strip() + " " + familyName.strip(), countryCode, now);
        p.givenName = givenName.strip();
        p.familyName = familyName.strip();
        p.dateOfBirth = dateOfBirth;
        return p;
    }

    private Person(UUID id, String legalName, String countryCode, Instant now) {
        super(id, legalName, null, countryCode, now);
    }

    @Override
    public PartyType type() {
        return PartyType.PERSON;
    }

    public String getGivenName() {
        return givenName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }
}
