package com.wbank.support;

import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.customer.CustomerService;
import com.wbank.customer.domain.Customer;
import com.wbank.deposit.DepositService;
import com.wbank.party.PartyService;
import com.wbank.party.domain.Person;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.Money;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds banking-domain state through the real services (every call commits). */
@Component
public class DomainFixtures {

    public final PartyService parties;
    public final CustomerService customers;
    public final AccountService accounts;
    public final DepositService deposits;
    private final CurrencyRegistry currencies;

    public DomainFixtures(PartyService parties, CustomerService customers, AccountService accounts,
                          DepositService deposits, CurrencyRegistry currencies) {
        this.parties = parties;
        this.customers = customers;
        this.accounts = accounts;
        this.deposits = deposits;
        this.currencies = currencies;
    }

    public Person person() {
        return parties.registerPerson("Test", "Person-" + UUID.randomUUID().toString().substring(0, 8),
                LocalDate.of(1990, 1, 1), "GB");
    }

    /** A party with an ACTIVE customer relationship. */
    public Customer activeCustomer() {
        Customer c = customers.open(person().getId(), null);
        return customers.activate(c.getId());
    }

    /** An ACTIVE deposit account (no overdraft) for a new active customer. */
    public Account activeAccount(String currency) {
        return activeAccount(activeCustomer().getId(), currency);
    }

    public Account activeAccount(UUID customerId, String currency) {
        Account a = accounts.openDepositAccount(customerId, "CURRENT", currencies.require(currency),
                Money.zero(currencies.require(currency)));
        return accounts.activate(a.getId());
    }

    public Money money(long minor, String currency) {
        return Money.ofMinorUnits(minor, currencies.require(currency));
    }

    public void fund(UUID accountId, long minor, String currency) {
        deposits.depositCash(accountId, money(minor, currency), "fixture funding", null);
    }

    public long ledgerBalance(UUID accountId) {
        return accounts.position(accountId).ledgerBalance().minorUnits();
    }
}
