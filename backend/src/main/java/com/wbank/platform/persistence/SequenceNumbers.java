package com.wbank.platform.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Allocates human-facing identifiers from PostgreSQL sequences.
 *
 * <p>Customer and account numbers are printed on statements and quoted over the phone,
 * so they must be short, stable and unique. UUIDs serve as internal identity; these
 * serve as external identity. Sequences are non-transactional by design — a rolled-back
 * onboarding burning a number is correct behaviour, because reusing a number that was
 * ever shown to a human is not acceptable.
 */
@Component
public class SequenceNumbers {

    private final JdbcTemplate jdbc;

    public SequenceNumbers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextCustomerNumber() {
        return "C" + nextValue("customer_number_seq");
    }

    public String nextAccountNumber() {
        return "A" + nextValue("account_number_seq");
    }

    public String nextObligationNumber() {
        return "L" + nextValue("obligation_number_seq");
    }

    private long nextValue(String sequenceName) {
        Long value = jdbc.queryForObject("SELECT nextval(?)", Long.class, sequenceName);
        if (value == null) {
            throw new IllegalStateException("Sequence " + sequenceName + " returned no value");
        }
        return value;
    }
}
