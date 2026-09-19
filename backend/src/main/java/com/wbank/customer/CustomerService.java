package com.wbank.customer;

import com.wbank.customer.domain.Customer;
import com.wbank.customer.domain.CustomerStatus;
import com.wbank.party.PartyService;
import com.wbank.party.domain.Party;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.persistence.SequenceNumbers;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle of customer relationships. Each public method is one transaction and one
 * explicit business action; there is no "set status" operation.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final PartyService parties;
    private final SequenceNumbers sequenceNumbers;
    private final AuditTrail auditTrail;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, PartyService parties, SequenceNumbers sequenceNumbers,
                           AuditTrail auditTrail, JdbcTemplate jdbc, Clock clock) {
        this.customers = customers;
        this.parties = parties;
        this.sequenceNumbers = sequenceNumbers;
        this.auditTrail = auditTrail;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Proposes a relationship with an existing party. It starts PENDING. */
    @Transactional
    public Customer open(UUID partyId, String email) {
        OperationContext context = RequestContext.forOperation("customer.open");
        Party party = parties.require(partyId);
        if (party.getId().equals(PartyService.INSTITUTION_PARTY_ID)) {
            throw new BusinessRuleViolationException("customer.institution",
                    "The institution cannot be its own customer");
        }
        if (customers.findByPartyId(partyId).isPresent()) {
            throw new ConflictException("customer.party_already_customer",
                    "Party " + partyId + " already has a customer relationship");
        }
        Customer customer = Customer.open(UUID.randomUUID(), sequenceNumbers.nextCustomerNumber(), partyId, email,
                clock.instant());
        try {
            customer = customers.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("customer.duplicate",
                    "A customer relationship for this party or email already exists", e);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customerNumber", customer.getCustomerNumber());
        payload.put("partyId", partyId.toString());
        payload.put("partyType", party.type().name());
        auditTrail.record("customer.opened", "customer", customer.getId(), context, payload);
        return customer;
    }

    @Transactional
    public Customer activate(UUID customerId) {
        return transition(customerId, "customer.activated", Customer::activate);
    }

    @Transactional
    public Customer suspend(UUID customerId) {
        return transition(customerId, "customer.suspended", Customer::suspend);
    }

    @Transactional
    public Customer reactivate(UUID customerId) {
        return transition(customerId, "customer.reactivated", Customer::reactivate);
    }

    /** Ends the relationship. Every account under it must already be closed. */
    @Transactional
    public Customer close(UUID customerId) {
        return transition(customerId, "customer.closed", (c, now) -> {
            Long open = jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE customer_id = ? AND status <> 'CLOSED'", Long.class, c.getId());
            if (open != null && open > 0) {
                throw new BusinessRuleViolationException("customer.has_open_accounts",
                        "Customer %s still has %d open account(s)".formatted(c.getId(), open));
            }
            c.close(now);
        });
    }

    @Transactional(readOnly = true)
    public Customer require(UUID customerId) {
        return customers.findById(customerId).orElseThrow(() -> NotFoundException.of("customer", customerId));
    }

    /** Loads the relationship with a row lock; used by other modules before starting new business. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Customer requireForUpdate(UUID customerId) {
        return customers.findByIdForUpdate(customerId).orElseThrow(() -> NotFoundException.of("customer", customerId));
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(Pageable pageable) {
        return customers.findAll(pageable);
    }

    private Customer transition(UUID customerId, String event, BiConsumer<Customer, Instant> action) {
        OperationContext context = RequestContext.forOperation(event);
        Customer customer = customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> NotFoundException.of("customer", customerId));
        CustomerStatus from = customer.getStatus();
        action.accept(customer, clock.instant());
        customers.save(customer);
        auditTrail.record(event, "customer", customerId, context,
                Map.of("from", from.name(), "to", customer.getStatus().name()));
        return customer;
    }
}
