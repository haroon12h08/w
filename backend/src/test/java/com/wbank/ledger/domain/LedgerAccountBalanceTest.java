package com.wbank.ledger.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LedgerAccountBalanceTest {

    private UUID accountId;
    private Instant now;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        now = Instant.now();
    }

    @Test
    @DisplayName("Asset account (debit-normal): debits increase balance, credits decrease balance")
    void testAssetAccountBalance() {
        // Floor of 0 (no overdraft permitted)
        LedgerAccountBalance balance = LedgerAccountBalance.openingZero(accountId, "USD", 0L, now);
        assertEquals(0L, balance.getBalanceMinor());

        // Debit $100 -> balance becomes +100
        balance.apply(NormalBalance.DEBIT, PostingDirection.DEBIT, 10000L, now);
        assertEquals(10000L, balance.getBalanceMinor());
        assertEquals(10000L, balance.getTotalDebitsMinor());
        assertEquals(0L, balance.getTotalCreditsMinor());
        assertEquals(1, balance.getPostingCount());

        // Credit $30 -> balance becomes +70
        balance.apply(NormalBalance.DEBIT, PostingDirection.CREDIT, 3000L, now);
        assertEquals(7000L, balance.getBalanceMinor());
        assertEquals(10000L, balance.getTotalDebitsMinor());
        assertEquals(3000L, balance.getTotalCreditsMinor());
        assertEquals(2, balance.getPostingCount());
    }

    @Test
    @DisplayName("Liability account (credit-normal): credits increase balance, debits decrease balance")
    void testLiabilityAccountBalance() {
        // Customer deposit account: credit-normal, floor 0
        LedgerAccountBalance balance = LedgerAccountBalance.openingZero(accountId, "USD", 0L, now);
        assertEquals(0L, balance.getBalanceMinor());

        // Credit $50 (customer deposit) -> liability balance becomes +50
        balance.apply(NormalBalance.CREDIT, PostingDirection.CREDIT, 5000L, now);
        assertEquals(5000L, balance.getBalanceMinor());

        // Debit $20 (customer withdrawal) -> liability balance becomes +30
        balance.apply(NormalBalance.CREDIT, PostingDirection.DEBIT, 2000L, now);
        assertEquals(3000L, balance.getBalanceMinor());
    }

    @Test
    @DisplayName("Floor breach check detects when debit would breach min_balance_minor")
    void testFloorBreachDetection() {
        // Floor of 0 (no overdraft)
        LedgerAccountBalance balance = LedgerAccountBalance.openingZero(accountId, "USD", 0L, now);

        // Current balance 0. Attempt to debit $10 from liability account -> would make balance -10 (breaches floor 0)
        assertTrue(balance.wouldBreachFloor(NormalBalance.CREDIT, PostingDirection.DEBIT, 1000L));

        // Credit $50 -> balance 50. Debit $10 -> would make balance 40 (does NOT breach floor 0)
        balance.apply(NormalBalance.CREDIT, PostingDirection.CREDIT, 5000L, now);
        assertFalse(balance.wouldBreachFloor(NormalBalance.CREDIT, PostingDirection.DEBIT, 1000L));

        // Debit $60 -> would make balance -10 (breaches floor 0)
        assertTrue(balance.wouldBreachFloor(NormalBalance.CREDIT, PostingDirection.DEBIT, 6000L));
    }
}
