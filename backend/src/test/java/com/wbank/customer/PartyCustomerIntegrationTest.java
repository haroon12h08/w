package com.wbank.customer;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.wbank.account.domain.Account;
import com.wbank.customer.domain.Customer;
import com.wbank.customer.domain.CustomerStatus;
import com.wbank.party.PartyService;
import com.wbank.party.domain.Organization;
import com.wbank.party.domain.Person;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Party vs customer: identity, relationship, lifecycle. Service layer and raw SQL. */
class PartyCustomerIntegrationTest extends PostgresIntegrationTest {

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return causeChain(t);
    }

    @Test
    void partiesAreRegisteredWithValidatedIdentity() {
        Person p = d.parties.registerPerson("Ada", "Lovelace", LocalDate.of(1815, 12, 10).plusYears(170), "GB");
        assertThat(p.getLegalName()).isEqualTo("Ada Lovelace");
        assertThat(d.parties.require(p.getId())).isInstanceOf(Person.class);

        String reg = "REG-" + UUID.randomUUID();
        Organization o = d.parties.registerOrganization("Acme Ltd", "Acme", reg, "gb");
        assertThat(o.getCountryCode()).isEqualTo("GB");
        assertThat(d.parties.require(o.getId())).isInstanceOf(Organization.class);

        assertThatThrownBy(() -> d.parties.registerOrganization("Acme 2", null, reg, "GB"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> d.parties.registerPerson("A", "B", null, "GB"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.parties.registerPerson("A", "B", LocalDate.now().plusDays(1), "GB"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.parties.registerPerson("A", "B", LocalDate.of(1990, 1, 1), "GBR"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.parties.registerOrganization("X", null, " ", "GB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPartyHasAtMostOneRelationshipAndTheInstitutionIsNotACustomer() {
        Person p = d.person();
        Customer c = d.customers.open(p.getId(), null);
        assertThat(c.getStatus()).isEqualTo(CustomerStatus.PENDING);
        assertThat(c.getPartyId()).isEqualTo(p.getId());
        assertThatThrownBy(() -> d.customers.open(p.getId(), null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> d.customers.open(PartyService.INSTITUTION_PARTY_ID, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(f.count("SELECT count(*) FROM party WHERE id = ?", PartyService.INSTITUTION_PARTY_ID)).isEqualTo(1);
    }

    @Test
    void validLifecycle() {
        UUID id = d.customers.open(d.person().getId(), null).getId();
        assertThat(d.customers.activate(id).getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(d.customers.suspend(id).getStatus()).isEqualTo(CustomerStatus.SUSPENDED);
        assertThat(d.customers.reactivate(id).getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        Customer closed = d.customers.close(id);
        assertThat(closed.getStatus()).isEqualTo(CustomerStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(f.count("SELECT count(*) FROM audit_event WHERE aggregate_id = ?", id)).isEqualTo(5);
    }

    @Test
    void invalidTransitionsAreRejected() {
        UUID pending = d.customers.open(d.person().getId(), null).getId();
        assertThatThrownBy(() -> d.customers.suspend(pending)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.customers.reactivate(pending)).isInstanceOf(InvalidStateTransitionException.class);

        UUID active = d.activeCustomer().getId();
        assertThatThrownBy(() -> d.customers.activate(active)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.customers.reactivate(active)).isInstanceOf(InvalidStateTransitionException.class);

        d.customers.close(active);
        assertThatThrownBy(() -> d.customers.activate(active)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.customers.reactivate(active)).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(d.customers.require(active).getStatus()).isEqualTo(CustomerStatus.CLOSED);
    }

    @Test
    void onlyAnActiveRelationshipMayStartNewBusiness() {
        UUID pending = d.customers.open(d.person().getId(), null).getId();
        assertThatThrownBy(() -> d.activeAccount(pending, "USD")).isInstanceOf(BusinessRuleViolationException.class);

        UUID suspended = d.activeCustomer().getId();
        Account existing = d.activeAccount(suspended, "USD");
        d.customers.suspend(suspended);
        assertThatThrownBy(() -> d.activeAccount(suspended, "USD")).isInstanceOf(BusinessRuleViolationException.class);
        // existing positions keep working while suspended
        d.fund(existing.getId(), 100, "USD");
        assertThat(d.ledgerBalance(existing.getId())).isEqualTo(100);
    }

    @Test
    void aRelationshipCannotCloseWhileAccountsAreOpen() {
        UUID c = d.activeCustomer().getId();
        Account a = d.activeAccount(c, "USD");
        assertThatThrownBy(() -> d.customers.close(c)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("open account");
        d.accounts.close(a.getId());
        assertThat(d.customers.close(c).getStatus()).isEqualTo(CustomerStatus.CLOSED);
    }

    @Test
    void databaseEnforcesPartyAndCustomerInvariants() {
        // a party without its subtype row
        UUID orphan = UUID.randomUUID();
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update(
                "INSERT INTO party (id, party_type, legal_name, country_code) VALUES (?, 'PERSON', 'Ghost', 'GB')", orphan))))
                .contains("has no person record");
        // person details on an organization
        UUID org = d.parties.registerOrganization("Org", null, "R-" + UUID.randomUUID(), "GB").getId();
        assertThat(rejection(() -> f.jdbc.update("INSERT INTO person (party_id) VALUES (?)", org)))
                .contains("person_party_fk");
        assertThat(rejection(() -> f.jdbc.update("UPDATE party SET party_type = 'PERSON' WHERE id = ?", org)))
                .contains("immutable");
        // customer born ACTIVE
        assertThat(rejection(() -> f.jdbc.update("""
                INSERT INTO customer (id, customer_number, party_id, status, onboarded_at, activated_at)
                VALUES (?, ?, ?, 'ACTIVE', now(), now())""", UUID.randomUUID(), "CX" + System.nanoTime(),
                d.person().getId()))).contains("starts PENDING");
        // closed customer resuming
        UUID c = d.activeCustomer().getId();
        d.customers.close(c);
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE customer SET status = 'ACTIVE', closed_at = NULL WHERE id = ?", c))).contains("is closed");
        // closing with open accounts, bypassing the service
        UUID c2 = d.activeCustomer().getId();
        d.activeAccount(c2, "USD");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE customer SET status = 'CLOSED', closed_at = now() WHERE id = ?", c2))).contains("open accounts");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM customer WHERE id = ?", c2))).contains("cannot be deleted");
    }
}
