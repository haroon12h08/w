package com.wbank.party;

import com.wbank.party.domain.Organization;
import com.wbank.party.domain.Party;
import com.wbank.party.domain.Person;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers parties. Registering a party creates no relationship with the bank and
 * no financial position; it only records that the entity exists.
 */
@Service
public class PartyService {

    /** The institution's own party (seeded by V7). Creditor of every loan the bank grants. */
    public static final UUID INSTITUTION_PARTY_ID = md5Uuid("wbank.institution");

    private final PartyRepository parties;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public PartyService(PartyRepository parties, AuditTrail auditTrail, Clock clock) {
        this.parties = parties;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional
    public Person registerPerson(String givenName, String familyName, LocalDate dateOfBirth, String countryCode) {
        Person person = Person.register(UUID.randomUUID(), givenName, familyName, dateOfBirth, countryCode,
                clock.instant());
        parties.saveAndFlush(person);
        audit(person, "party.person_registered");
        return person;
    }

    @Transactional
    public Organization registerOrganization(String legalName, String displayName, String registrationNumber,
                                             String countryCode) {
        Organization org = Organization.register(UUID.randomUUID(), legalName, displayName, registrationNumber,
                countryCode, clock.instant());
        try {
            parties.saveAndFlush(org);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("party.duplicate_registration",
                    "An organization with registration number " + registrationNumber + " already exists", e);
        }
        audit(org, "party.organization_registered");
        return org;
    }

    @Transactional(readOnly = true)
    public Party require(UUID id) {
        return parties.findById(id).orElseThrow(() -> NotFoundException.of("party", id));
    }

    private void audit(Party party, String event) {
        OperationContext context = RequestContext.forOperation(event);
        auditTrail.record(event, "party", party.getId(), context,
                Map.of("partyType", party.type().name(), "legalName", party.getLegalName(),
                        "countryCode", party.getCountryCode()));
    }

    /** Same derivation as PostgreSQL's {@code md5(text)::uuid}. */
    static UUID md5Uuid(String seed) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d);
            return new UUID(b.getLong(), b.getLong());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
