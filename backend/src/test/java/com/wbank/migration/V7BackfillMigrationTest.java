package com.wbank.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.wbank.support.PostgresIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V7 restructures live data (customer -> party + relationship, deposit_account -> account).
 * This migrates an isolated schema to V6, writes pre-V7 data the way the V6 application did,
 * then upgrades to V7 and checks that nothing was lost and every new invariant holds.
 */
class V7BackfillMigrationTest extends PostgresIntegrationTest {

    @Test
    void preV7CustomersAndDepositAccountsSurviveTheUpgrade() {
        String schema = "backfill_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").target("6").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID alice = UUID.randomUUID();
        UUID acme = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customer (id, customer_number, customer_type, legal_name, country_code, email, status, onboarded_at)
                VALUES (?, 'C1', 'INDIVIDUAL', 'Alice Smith', 'GB', 'a@x.test', 'ACTIVE', now()),
                       (?, 'C2', 'ORGANISATION', 'Acme Ltd', 'US', NULL, 'SUSPENDED', now())""", alice, acme);
        UUID ledgerA = UUID.randomUUID();
        UUID ledgerB = UUID.randomUUID();
        for (UUID l : new UUID[] {ledgerA, ledgerB}) {
            jdbc.update("""
                    INSERT INTO ledger_account (id, code, name, account_type, normal_balance, currency, purpose, status)
                    VALUES (?, ?, 'dep', 'LIABILITY', 'CREDIT', 'USD', 'CUSTOMER_DEPOSIT', 'ACTIVE')""", l, "2000-" + l);
            jdbc.update("INSERT INTO ledger_account_balance (ledger_account_id, currency, min_balance_minor) VALUES (?, 'USD', 0)", l);
        }
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO deposit_account (id, account_number, customer_id, ledger_account_id, currency, product_code,
                    status, opened_at) VALUES (?, 'A1', ?, ?, 'USD', 'CURRENT', 'ACTIVE', now()),
                                              (?, 'A2', ?, ?, 'USD', 'SAVINGS', 'FROZEN', now())""",
                depA, alice, ledgerA, depB, acme, ledgerB);

        Flyway.configure().dataSource(ds).schemas(schema).locations("classpath:db/migration").load().migrate();

        Map<String, Object> a = jdbc.queryForMap("""
                SELECT c.status, c.email, p.party_type, p.legal_name, p.country_code
                  FROM customer c JOIN party p ON p.id = c.party_id WHERE c.id = ?""", alice);
        assertThat(a).containsEntry("status", "ACTIVE").containsEntry("email", "a@x.test")
                .containsEntry("party_type", "PERSON").containsEntry("legal_name", "Alice Smith");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM person p JOIN customer c ON c.party_id = p.party_id WHERE c.id = ?",
                Long.class, alice)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT p.party_type FROM customer c JOIN party p ON p.id = c.party_id WHERE c.id = ?",
                String.class, acme)).isEqualTo("ORGANIZATION");

        Map<String, Object> accB = jdbc.queryForMap(
                "SELECT a.status, a.product_family, a.owner_party_id = c.party_id AS owner_ok, la.status AS ledger_status"
                        + " FROM account a JOIN customer c ON c.id = a.customer_id"
                        + " JOIN ledger_account la ON la.id = a.ledger_account_id WHERE a.id = ?", depB);
        assertThat(accB).containsEntry("status", "FROZEN").containsEntry("product_family", "DEPOSIT")
                .containsEntry("owner_ok", true)
                // the pre-V7 hole: a frozen deposit whose ledger account still accepted postings
                .containsEntry("ledger_status", "FROZEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class)).isEqualTo(2);
    }
}
