package com.wbank.account;

import static com.wbank.support.LedgerFixtures.causeChain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.wbank.account.domain.Account;
import com.wbank.account.domain.AccountStatus;
import com.wbank.ledger.JournalService;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountStatus;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.platform.error.InvalidStateTransitionException;
import com.wbank.platform.money.Money;
import com.wbank.product.ProductService;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired JournalService journals;
    @Autowired ProductService products;

    private String rejection(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).as("expected rejection").isNotNull();
        return causeChain(t);
    }

    private LedgerAccount ledgerOf(Account a) {
        return f.ledger.requireAccount(d.accounts.require(a.getId()).getLedgerAccountId());
    }

    @Test
    void pendingAccountHasNoLedgerPositionAndCannotTransact() {
        var customer = d.activeCustomer();
        Account a = d.accounts.openDepositAccount(customer.getId(), "CURRENT", f.currency("USD"), f.money(0, "USD"));
        assertThat(a.getStatus()).isEqualTo(AccountStatus.PENDING);
        assertThat(a.getLedgerAccountId()).isNull();
        assertThat(a.getOwnerPartyId()).isEqualTo(customer.getPartyId());
        assertThatThrownBy(() -> d.fund(a.getId(), 100, "USD")).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void activationCreatesAMatchingLedgerPosition() {
        var customer = d.activeCustomer();
        Account pending = d.accounts.openDepositAccount(customer.getId(), "CURRENT", f.currency("EUR"),
                f.money(5_000, "EUR"));
        Account a = d.accounts.activate(pending.getId());
        LedgerAccount la = ledgerOf(a);
        assertThat(la.getAccountType()).isEqualTo(LedgerAccountType.LIABILITY);
        assertThat(la.getCurrencyCode()).isEqualTo("EUR");
        assertThat(la.getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(f.ledger.requireBalance(la.getId()).getMinBalanceMinor()).isEqualTo(-5_000);
        // overdraft usable to exactly the limit, not beyond
        d.deposits.withdrawCash(a.getId(), f.money(5_000, "EUR"), null, null);
        assertThat(d.ledgerBalance(a.getId())).isEqualTo(-5_000);
        assertThatThrownBy(() -> d.deposits.withdrawCash(a.getId(), f.money(1, "EUR"), null, null))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void freezeIsMirroredInTheLedgerSoNoWritePathCanMoveMoney() {
        Account a = d.activeAccount("USD");
        d.fund(a.getId(), 1_000, "USD");
        d.accounts.freeze(a.getId());
        assertThat(ledgerOf(a).getStatus()).isEqualTo(LedgerAccountStatus.FROZEN);

        assertThatThrownBy(() -> d.fund(a.getId(), 1, "USD")).isInstanceOf(BusinessRuleViolationException.class);
        // even the ledger primitive, called directly, refuses
        LedgerAccount cash = f.account(LedgerAccountType.ASSET, "USD");
        UUID ledgerId = ledgerOf(a).getId();
        assertThatThrownBy(() -> f.move(cash.getId(), ledgerId, 1, "USD", null))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("cannot accept postings");

        d.accounts.unfreeze(a.getId());
        assertThat(ledgerOf(a).getStatus()).isEqualTo(LedgerAccountStatus.ACTIVE);
        d.fund(a.getId(), 1, "USD");
        assertThat(d.ledgerBalance(a.getId())).isEqualTo(1_001);
    }

    @Test
    void manualLedgerJournalsCannotTouchProductPositions() {
        Account a = d.activeAccount("USD");
        LedgerAccount equity = f.account(LedgerAccountType.EQUITY, "USD");
        assertThatThrownBy(() -> journals.post(f.request("USD", "k-" + UUID.randomUUID(),
                PostingInstruction.debit(equity.getId(), f.money(10, "USD")),
                PostingInstruction.credit(ledgerOf(a).getId(), f.money(10, "USD")))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("product");
        assertThat(d.ledgerBalance(a.getId())).isZero();
    }

    @Test
    void closeRequiresZeroBalanceAndIsTerminalEverywhere() {
        Account a = d.activeAccount("USD");
        d.fund(a.getId(), 500, "USD");
        assertThatThrownBy(() -> d.accounts.close(a.getId())).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(d.accounts.require(a.getId()).getStatus()).isEqualTo(AccountStatus.ACTIVE);

        d.deposits.withdrawCash(a.getId(), f.money(500, "USD"), null, null);
        d.accounts.close(a.getId());
        assertThat(ledgerOf(a).getStatus()).isEqualTo(LedgerAccountStatus.CLOSED);

        assertThatThrownBy(() -> d.accounts.unfreeze(a.getId())).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.accounts.activate(a.getId())).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.accounts.freeze(a.getId())).isInstanceOf(InvalidStateTransitionException.class);
        LedgerAccount cash = f.account(LedgerAccountType.ASSET, "USD");
        assertThatThrownBy(() -> f.move(cash.getId(), ledgerOf(a).getId(), 1, "USD", null))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void invalidTransitions() {
        var customer = d.activeCustomer();
        Account pending = d.accounts.openDepositAccount(customer.getId(), "SAVINGS", f.currency("USD"), f.money(0, "USD"));
        assertThatThrownBy(() -> d.accounts.freeze(pending.getId())).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.accounts.unfreeze(pending.getId())).isInstanceOf(InvalidStateTransitionException.class);
        d.accounts.activate(pending.getId());
        assertThatThrownBy(() -> d.accounts.activate(pending.getId())).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> d.accounts.unfreeze(pending.getId())).isInstanceOf(InvalidStateTransitionException.class);
        d.accounts.freeze(pending.getId());
        // a hold cannot be dodged by closing
        assertThatThrownBy(() -> d.accounts.close(pending.getId())).isInstanceOf(InvalidStateTransitionException.class);
        // a pending account may be abandoned
        Account abandoned = d.accounts.openDepositAccount(customer.getId(), "SAVINGS", f.currency("USD"), f.money(0, "USD"));
        assertThat(d.accounts.close(abandoned.getId()).getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void productRules() {
        var customer = d.activeCustomer();
        assertThatThrownBy(() -> d.accounts.openDepositAccount(customer.getId(), "SAVINGS", f.currency("USD"),
                f.money(100, "USD"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("overdraft");
        assertThatThrownBy(() -> d.accounts.openDepositAccount(customer.getId(), "TERM_LOAN", f.currency("USD"),
                f.money(0, "USD"))).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void anAccountStoresNoBalance() {
        assertThat(Arrays.stream(Account.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
                .noneMatch(n -> n.toLowerCase().contains("balance"));
        assertThat(f.count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'account'"
                + " AND column_name LIKE '%balance%'")).isZero();
    }

    @Test
    void concurrentCloseAndDepositNeverLeaveMoneyInAClosedAccount() throws Exception {
        for (int i = 0; i < 10; i++) {
            Account a = d.activeAccount("USD");
            CompletableFuture<Throwable> close = CompletableFuture.supplyAsync(() -> catchThrowable(() -> d.accounts.close(a.getId())));
            CompletableFuture<Throwable> dep = CompletableFuture.supplyAsync(() -> catchThrowable(() -> d.fund(a.getId(), 100, "USD")));
            close.get();
            dep.get();
            Account after = d.accounts.require(a.getId());
            long bal = d.ledgerBalance(a.getId());
            if (after.getStatus() == AccountStatus.CLOSED) {
                assertThat(bal).isZero();
            } else {
                assertThat(bal).isEqualTo(100);
            }
        }
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }

    @Test
    void databaseEnforcesAccountInvariants() {
        Account a = d.activeAccount("USD");
        // ledger status drifting away from the account
        assertThat(rejection(() -> f.tx.executeWithoutResult(s -> f.jdbc.update(
                "UPDATE ledger_account SET status = 'FROZEN' WHERE id = ?", a.getLedgerAccountId()))))
                .contains("is ACTIVE but its ledger account");
        // invalid transitions
        assertThat(rejection(() -> f.jdbc.update("UPDATE account SET status = 'PENDING' WHERE id = ?", a.getId())))
                .contains("cannot move from ACTIVE to PENDING");
        d.fund(a.getId(), 10, "USD");
        assertThat(rejection(() -> f.jdbc.update(
                "UPDATE account SET status = 'CLOSED', closed_at = now() WHERE id = ?", a.getId())))
                .contains("non-zero ledger balance");
        // ownership cannot be re-pointed
        UUID otherParty = d.person().getId();
        assertThat(rejection(() -> f.jdbc.update("UPDATE account SET owner_party_id = ? WHERE id = ?", otherParty, a.getId())))
                .contains("immutable");
        // an owner that does not match the customer relationship
        var customer = d.activeCustomer();
        assertThat(rejection(() -> f.jdbc.update("""
                INSERT INTO account (id, account_number, customer_id, owner_party_id, product_code, product_family,
                    currency, status, overdraft_limit_minor, opened_at)
                VALUES (?, ?, ?, ?, 'CURRENT', 'DEPOSIT', 'USD', 'PENDING', 0, now())""",
                UUID.randomUUID(), "AX" + System.nanoTime(), customer.getId(), otherParty)))
                .contains("account_owner_matches_customer");
        assertThat(rejection(() -> f.jdbc.update("""
                INSERT INTO account (id, account_number, customer_id, owner_party_id, product_code, product_family,
                    currency, status, overdraft_limit_minor, opened_at)
                VALUES (?, ?, ?, ?, 'SAVINGS', 'DEPOSIT', 'USD', 'PENDING', 100, now())""",
                UUID.randomUUID(), "AX" + System.nanoTime(), customer.getId(), customer.getPartyId())))
                .contains("does not permit an overdraft");
        assertThat(rejection(() -> f.jdbc.update("DELETE FROM account WHERE id = ?", a.getId())))
                .contains("cannot be deleted");
    }

    @Test
    void withdrawnProductsStopNewSalesOnly() {
        // use a dedicated product so other tests are unaffected
        f.jdbc.update("INSERT INTO product (code, name, family, status, allows_overdraft) VALUES (?, 'tmp', 'DEPOSIT', 'ACTIVE', false) ON CONFLICT DO NOTHING", "TMP_SAVER");
        var customer = d.activeCustomer();
        Account existing = d.accounts.activate(d.accounts.openDepositAccount(customer.getId(), "TMP_SAVER",
                f.currency("USD"), f.money(0, "USD")).getId());
        products.withdraw("TMP_SAVER");
        assertThatThrownBy(() -> d.accounts.openDepositAccount(customer.getId(), "TMP_SAVER", f.currency("USD"),
                Money.zero(f.currency("USD")))).isInstanceOf(BusinessRuleViolationException.class);
        d.fund(existing.getId(), 42, "USD");
        assertThat(d.ledgerBalance(existing.getId())).isEqualTo(42);
        assertThatThrownBy(() -> products.withdraw("TMP_SAVER")).isInstanceOf(InvalidStateTransitionException.class);
    }
}
