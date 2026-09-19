package com.wbank.ledger;

import static com.wbank.ledger.domain.PostingInstruction.credit;
import static com.wbank.ledger.domain.PostingInstruction.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.NormalBalance;
import com.wbank.ledger.domain.PostingDirection;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.error.UnbalancedEntryException;
import com.wbank.platform.money.Money;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Financial invariants of the ledger, exercised through the real service layer against
 * real PostgreSQL, with every operation COMMITTED. See docs/ledger.md for the invariant list.
 */
class LedgerInvariantsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    LedgerFixtures f;

    LedgerAccount cash;      // ASSET, debit-normal, no floor
    LedgerAccount customer;  // LIABILITY, credit-normal, floor 0

    @BeforeEach
    void accounts() {
        cash = f.account(LedgerAccountType.ASSET, "USD");
        customer = f.customerAccount("USD");
    }

    @Nested
    @DisplayName("1. A valid balanced journal can be posted")
    class ValidPosting {

        @Test
        void balancedJournalIsCommittedWithAllLegsAndCorrectBalances() {
            PostedEntry posted = f.move(cash.getId(), customer.getId(), 10_000, "USD", null);

            UUID entryId = posted.entry().getId();
            assertThat(f.count("SELECT count(*) FROM journal_entry WHERE id = ? AND status = 'POSTED'", entryId))
                    .isEqualTo(1);
            assertThat(f.count("SELECT count(*) FROM posting WHERE journal_entry_id = ?", entryId)).isEqualTo(2);
            assertThat(f.count("SELECT SUM(signed_amount_minor) FROM posting WHERE journal_entry_id = ?", entryId))
                    .isZero();
            assertThat(posted.entry().getTotalAmountMinor()).isEqualTo(10_000);

            // Debit increases an asset; credit increases a liability. Both balances +100.00.
            assertThat(f.projected(cash.getId())).isEqualTo(10_000);
            assertThat(f.projected(customer.getId())).isEqualTo(10_000);
            assertThat(f.derived(cash.getId())).isEqualTo(10_000);
            assertThat(f.derived(customer.getId())).isEqualTo(10_000);
        }

        @Test
        void compoundJournalWithManyLegsBalancesAsAWhole() {
            LedgerAccount revenue = f.account(LedgerAccountType.REVENUE, "USD");
            LedgerAccount expense = f.account(LedgerAccountType.EXPENSE, "USD");
            // 150.00 cash in = 100.00 to the customer + 50.00 fee revenue; plus 7.25 expense paid from cash.
            f.post(f.request("USD", null,
                    debit(cash.getId(), f.money(15_000, "USD")),
                    credit(customer.getId(), f.money(10_000, "USD")),
                    credit(revenue.getId(), f.money(5_000, "USD")),
                    debit(expense.getId(), f.money(725, "USD")),
                    credit(cash.getId(), f.money(725, "USD"))));

            assertThat(f.projected(cash.getId())).isEqualTo(14_275);
            assertThat(f.projected(customer.getId())).isEqualTo(10_000);
            assertThat(f.projected(revenue.getId())).isEqualTo(5_000);
            assertThat(f.projected(expense.getId())).isEqualTo(725);
        }
    }

    @Nested
    @DisplayName("2. An unbalanced journal is rejected")
    class Unbalanced {

        @Test
        void debitsNotEqualToCreditsIsRejectedBeforeAnyWrite() {
            long entriesBefore = f.count("SELECT count(*) FROM journal_entry");
            assertThatThrownBy(() -> f.request("USD", null,
                    debit(cash.getId(), f.money(10_000, "USD")),
                    credit(customer.getId(), f.money(9_999, "USD"))))
                    .isInstanceOf(UnbalancedEntryException.class)
                    .hasMessageContaining("differ by 1 minor units");
            assertThat(f.count("SELECT count(*) FROM journal_entry")).isEqualTo(entriesBefore);
        }

        @Test
        void singleLegAndOneSidedJournalsAreRejected() {
            assertThatThrownBy(() -> f.request("USD", null, debit(cash.getId(), f.money(1, "USD"))))
                    .isInstanceOf(UnbalancedEntryException.class);
            assertThatThrownBy(() -> f.request("USD", null,
                    debit(cash.getId(), f.money(1, "USD")), debit(customer.getId(), f.money(1, "USD"))))
                    .isInstanceOf(UnbalancedEntryException.class);
        }
    }

    @Nested
    @DisplayName("3. A journal cannot contain an invalid monetary amount")
    class InvalidAmounts {

        @Test
        void zeroAndNegativeAmountsAreRejected() {
            assertThatThrownBy(() -> debit(cash.getId(), f.money(0, "USD")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> credit(cash.getId(), f.money(-500, "USD")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void precisionFinerThanTheCurrencyScaleIsRejectedNotRounded() {
            assertThatThrownBy(() -> Money.ofMajorUnits(new BigDecimal("10.005"), f.currency("USD")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 decimal");
            assertThatThrownBy(() -> Money.ofMajorUnits(new BigDecimal("100.5"), f.currency("JPY")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 decimal");
            // Trailing zeros are not extra precision.
            assertThat(Money.ofMajorUnits(new BigDecimal("10.500"), f.currency("USD")).minorUnits()).isEqualTo(1050);
            assertThat(Money.ofMajorUnits(new BigDecimal("100"), f.currency("JPY")).minorUnits()).isEqualTo(100);
        }

        @Test
        void amountsBeyondTheRepresentableRangeAreRejectedNotWrapped() {
            assertThatThrownBy(() -> Money.ofMajorUnits(new BigDecimal("100000000000000000000"), f.currency("USD")))
                    .isInstanceOf(IllegalArgumentException.class);
            Money huge = Money.ofMinorUnits(Long.MAX_VALUE, f.currency("USD"));
            assertThatThrownBy(() -> f.request("USD", null,
                    debit(cash.getId(), huge), debit(cash.getId(), huge),
                    credit(customer.getId(), huge), credit(customer.getId(), huge)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("range");
        }
    }

    @Nested
    @DisplayName("4. A journal cannot mix currencies incorrectly")
    class Currencies {

        @Test
        void legsInDifferentCurrenciesAreRejected() {
            LedgerAccount eur = f.account(LedgerAccountType.ASSET, "EUR");
            assertThatThrownBy(() -> f.request("USD", null,
                    debit(cash.getId(), f.money(100, "USD")), credit(eur.getId(), f.money(100, "EUR"))))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        void aJournalCannotPostToAnAccountDenominatedInAnotherCurrency() {
            LedgerAccount eurA = f.account(LedgerAccountType.ASSET, "EUR");
            long before = f.postingsOn(eurA.getId());
            // Legs are all "EUR", but cash is a USD account.
            assertThatThrownBy(() -> f.post(f.request("EUR", null,
                    debit(eurA.getId(), f.money(100, "EUR")), credit(cash.getId(), f.money(100, "EUR")))))
                    .isInstanceOf(CurrencyMismatchException.class);
            assertThat(f.postingsOn(eurA.getId())).isEqualTo(before);
        }

        @Test
        void unknownCurrenciesAreRejected() {
            assertThatThrownBy(() -> f.currency("XXX")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> f.currency("usd1")).isInstanceOf(NotFoundException.class);
        }

        @Test
        void currenciesWithDifferentScalesAreEachExact() {
            LedgerAccount jpyA = f.account(LedgerAccountType.ASSET, "JPY");
            LedgerAccount jpyL = f.account(LedgerAccountType.LIABILITY, "JPY");
            f.move(jpyA.getId(), jpyL.getId(), 1_234, "JPY", null); // ¥1,234 - minor unit == major unit
            assertThat(f.projected(jpyL.getId())).isEqualTo(1_234);
        }
    }

    @Nested
    @DisplayName("5. A failed posting leaves no partial financial state")
    class Atomicity {

        @Test
        void aRollbackAfterPostingRemovesEveryTraceOfTheJournal() {
            String key = "rollback-" + UUID.randomUUID();
            JournalEntryRequest req = f.request("USD", key,
                    debit(cash.getId(), f.money(5_000, "USD")), credit(customer.getId(), f.money(5_000, "USD")));

            assertThatThrownBy(() -> f.tx.executeWithoutResult(s -> {
                f.ledger.post(req);
                throw new IllegalStateException("downstream failure in the same unit of work");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(f.entriesWithKey(key)).isZero();
            assertThat(f.postingsOn(cash.getId())).isZero();
            assertThat(f.postingsOn(customer.getId())).isZero();
            assertThat(f.projected(cash.getId())).isZero();
            assertThat(f.projected(customer.getId())).isZero();
            assertThat(f.count("SELECT count(*) FROM audit_event WHERE payload->>'idempotencyKey' = ?", key)).isZero();

            // The key was never consumed: a retry succeeds exactly once.
            PostedEntry retried = f.post(req);
            assertThat(retried.replayed()).isFalse();
            assertThat(f.projected(customer.getId())).isEqualTo(5_000);
        }

        @Test
        void aJournalRejectedOnItsLastLegLeavesEarlierLegsUnapplied() {
            LedgerAccount other = f.account(LedgerAccountType.ASSET, "USD");
            // legs 1-2 are fine on their own; leg 3 overdraws the customer account (floor 0).
            JournalEntryRequest req = f.request("USD", null,
                    debit(cash.getId(), f.money(300, "USD")),
                    credit(other.getId(), f.money(300, "USD")),
                    debit(customer.getId(), f.money(1, "USD")),
                    credit(cash.getId(), f.money(1, "USD")));
            assertThatThrownBy(() -> f.post(req)).isInstanceOf(InsufficientFundsException.class);

            for (LedgerAccount a : List.of(cash, other, customer)) {
                assertThat(f.postingsOn(a.getId())).isZero();
                assertThat(f.projected(a.getId())).isZero();
            }
        }

        @Test
        void postingOutsideATransactionIsRefused() {
            // The ledger will not open its own transaction: the caller's use case must own it,
            // so that the caller's bookkeeping and the money movement commit together.
            assertThatThrownBy(() -> f.ledger.post(f.request("USD", null,
                    debit(cash.getId(), f.money(1, "USD")), credit(customer.getId(), f.money(1, "USD")))))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThat(f.postingsOn(cash.getId())).isZero();
        }
    }

    @Nested
    @DisplayName("6. Duplicate requests do not produce duplicate financial effects")
    class Idempotency {

        @Test
        void resubmittingTheSameKeyReplaysTheOriginalEntry() {
            String key = "dup-" + UUID.randomUUID();
            JournalEntryRequest req = f.request("USD", key,
                    debit(cash.getId(), f.money(2_500, "USD")), credit(customer.getId(), f.money(2_500, "USD")));

            PostedEntry first = f.post(req);
            PostedEntry second = f.post(req);
            PostedEntry third = f.post(req);

            assertThat(first.replayed()).isFalse();
            assertThat(second.replayed()).isTrue();
            assertThat(third.entry().getId()).isEqualTo(first.entry().getId());
            assertThat(second.postings()).extracting(p -> p.getId())
                    .containsExactlyElementsOf(first.postings().stream().map(p -> p.getId()).toList());
            assertThat(f.entriesWithKey(key)).isEqualTo(1);
            assertThat(f.projected(customer.getId())).isEqualTo(2_500);
            assertThat(f.postingsOn(customer.getId())).isEqualTo(1);
        }

        @Test
        void reusingAKeyForADifferentRequestIsAConflictNotAReplay() {
            String key = "reuse-" + UUID.randomUUID();
            f.move(cash.getId(), customer.getId(), 2_500, "USD", key);

            assertThatThrownBy(() -> f.move(cash.getId(), customer.getId(), 2_600, "USD", key))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("different request");
            assertThat(f.entriesWithKey(key)).isEqualTo(1);
            assertThat(f.projected(customer.getId())).isEqualTo(2_500);
        }

        @Test
        void aRetryWithANewCorrelationIdIsStillTheSameRequest() {
            String key = "corr-" + UUID.randomUUID();
            f.move(cash.getId(), customer.getId(), 700, "USD", key);
            // f.request() mints a fresh correlation id each call (RequestContext off-request).
            PostedEntry again = f.move(cash.getId(), customer.getId(), 700, "USD", key);
            assertThat(again.replayed()).isTrue();
            assertThat(f.projected(customer.getId())).isEqualTo(700);
        }
    }

    @Nested
    @DisplayName("7. Historical posted entries are corrected by new entries, never rewritten")
    class Corrections {

        @Test
        void reversalIsAMirrorEntryAndTheOriginalIsPreservedVerbatim() {
            PostedEntry original = f.move(cash.getId(), customer.getId(), 8_000, "USD", null);
            UUID id = original.entry().getId();
            List<Map<String, Object>> legsBefore = f.jdbc.queryForList(
                    "SELECT id, ledger_account_id, direction, amount_minor, balance_after_minor, account_sequence"
                            + " FROM posting WHERE journal_entry_id = ? ORDER BY entry_leg", id);

            PostedEntry reversal = f.tx.execute(s -> f.ledger.reverse(id, "keyed in error",
                    RequestContext.forOperation("test.reverse")));

            // History is intact...
            assertThat(f.jdbc.queryForList(
                    "SELECT id, ledger_account_id, direction, amount_minor, balance_after_minor, account_sequence"
                            + " FROM posting WHERE journal_entry_id = ? ORDER BY entry_leg", id))
                    .isEqualTo(legsBefore);
            // ...the original is linked to its correction...
            assertThat(f.jdbc.queryForMap(
                    "SELECT status, reversed_by_entry_id FROM journal_entry WHERE id = ?", id))
                    .containsEntry("status", "REVERSED")
                    .containsEntry("reversed_by_entry_id", reversal.entry().getId());
            // ...the correction mirrors it leg by leg...
            assertThat(reversal.entry().getEntryType()).isEqualTo(JournalEntryType.REVERSAL);
            assertThat(reversal.entry().getReversesEntryId()).isEqualTo(id);
            for (int i = 0; i < 2; i++) {
                assertThat(reversal.postings().get(i).getDirection())
                        .isEqualTo(original.postings().get(i).getDirection().opposite());
                assertThat(reversal.postings().get(i).getAmountMinor())
                        .isEqualTo(original.postings().get(i).getAmountMinor());
            }
            // ...and the net financial effect is nil while the evidence of both remains.
            assertThat(f.projected(customer.getId())).isZero();
            assertThat(f.postingsOn(customer.getId())).isEqualTo(2);
        }

        @Test
        void anEntryCanBeReversedOnlyOnceAndAReversalCannotBeReversed() {
            UUID id = f.move(cash.getId(), customer.getId(), 100, "USD", null).entry().getId();
            PostedEntry reversal = f.tx.execute(s -> f.ledger.reverse(id, "first", RequestContext.forOperation("t")));

            assertThatThrownBy(() -> f.tx.execute(s -> f.ledger.reverse(id, "again", RequestContext.forOperation("t"))))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> f.tx.execute(s -> f.ledger.reverse(reversal.entry().getId(), "undo",
                    RequestContext.forOperation("t"))))
                    .isInstanceOf(BusinessRuleViolationException.class);
            assertThat(f.projected(customer.getId())).isZero();
        }

        @Test
        void aReversalMayNotDriveAnAccountBelowItsFloor() {
            // Customer receives 100, spends 60, then someone tries to reverse the 100 credit.
            UUID in = f.move(cash.getId(), customer.getId(), 10_000, "USD", null).entry().getId();
            f.move(customer.getId(), cash.getId(), 6_000, "USD", null);
            assertThatThrownBy(() -> f.tx.execute(s -> f.ledger.reverse(in, "chargeback",
                    RequestContext.forOperation("t"))))
                    .isInstanceOf(InsufficientFundsException.class);
            assertThat(f.projected(customer.getId())).isEqualTo(4_000);
        }

        @Test
        void thereIsNoApplicationPathThatSetsABalance() {
            // Structural assertion: neither the service nor the balance entity exposes a setter
            // for the balance amount. The only mutator is apply(direction, amount), called from
            // the posting path.
            assertThat(java.util.Arrays.stream(LedgerService.class.getMethods()).map(m -> m.getName()))
                    .noneMatch(n -> n.toLowerCase().contains("setbalance") || n.toLowerCase().contains("updatebalance"));
            assertThat(java.util.Arrays.stream(com.wbank.ledger.domain.LedgerAccountBalance.class.getMethods())
                    .map(m -> m.getName()))
                    .doesNotContain("setBalanceMinor", "setTotalDebitsMinor", "setTotalCreditsMinor");
        }
    }

    @Nested
    @DisplayName("8. Balances derived from the ledger are mathematically consistent")
    class DerivedBalances {

        @Test
        void randomisedJournalsAgreeWithAnIndependentModel() {
            List<LedgerAccount> accts = new ArrayList<>();
            for (LedgerAccountType t : LedgerAccountType.values()) {
                accts.add(f.account(t, "EUR"));
                accts.add(f.account(t, "EUR"));
            }
            Map<UUID, Long> rawDebitPositive = new HashMap<>();
            Map<UUID, Long> debits = new HashMap<>();
            Map<UUID, Long> credits = new HashMap<>();
            Random rnd = new Random(20260919L); // fixed seed: reproducible

            for (int j = 0; j < 150; j++) {
                int legsPerSide = 1 + rnd.nextInt(3);
                List<PostingInstruction> legs = new ArrayList<>();
                long debitSum = 0;
                for (int i = 0; i < legsPerSide; i++) {
                    long amt = 1 + rnd.nextInt(1_000_000);
                    debitSum += amt;
                    legs.add(debit(accts.get(rnd.nextInt(accts.size())).getId(), f.money(amt, "EUR")));
                }
                // Split the same total across the credit side so the entry balances by construction.
                long remaining = debitSum;
                for (int i = 0; i < legsPerSide; i++) {
                    long amt = i == legsPerSide - 1 ? remaining : 1 + (long) (rnd.nextDouble() * (remaining - (legsPerSide - i - 1)));
                    amt = Math.max(1, Math.min(amt, remaining - (legsPerSide - i - 1)));
                    remaining -= amt;
                    legs.add(credit(accts.get(rnd.nextInt(accts.size())).getId(), f.money(amt, "EUR")));
                }
                f.post(new JournalEntryRequest(JournalEntryType.ADJUSTMENT, "random " + j, LocalDate.of(2026, 9, 19),
                        f.currency("EUR"), legs, null, RequestContext.forOperation("test.random")));
                for (PostingInstruction p : legs) {
                    long m = p.amount().minorUnits();
                    rawDebitPositive.merge(p.ledgerAccountId(), p.direction() == PostingDirection.DEBIT ? m : -m, Long::sum);
                    (p.direction() == PostingDirection.DEBIT ? debits : credits).merge(p.ledgerAccountId(), m, Long::sum);
                }
            }

            long accountingEquation = 0; // (assets + expenses) - (liabilities + equity + revenue)
            for (LedgerAccount a : accts) {
                UUID id = a.getId();
                long raw = rawDebitPositive.getOrDefault(id, 0L);
                long expected = a.getNormalBalance() == NormalBalance.DEBIT ? raw : -raw;
                accountingEquation += a.getNormalBalance() == NormalBalance.DEBIT ? expected : -expected;

                // projection == independent Java model == SQL sum over postings
                assertThat(f.projected(id)).as("projection %s", a.getCode()).isEqualTo(expected);
                assertThat(f.derived(id)).as("derived %s", a.getCode()).isEqualTo(expected);
                Map<String, Object> row = f.jdbc.queryForMap(
                        "SELECT total_debits_minor, total_credits_minor, posting_count, last_sequence"
                                + " FROM ledger_account_balance WHERE ledger_account_id = ?", id);
                assertThat(row.get("total_debits_minor")).isEqualTo(debits.getOrDefault(id, 0L));
                assertThat(row.get("total_credits_minor")).isEqualTo(credits.getOrDefault(id, 0L));
                long n = f.postingsOn(id);
                assertThat(row.get("posting_count")).isEqualTo(n);

                // The per-account chain is gap-free (1..n) and every running balance is the
                // cumulative sum of the postings before it.
                List<Map<String, Object>> chain = f.jdbc.queryForList(
                        "SELECT account_sequence, direction, amount_minor, balance_after_minor FROM posting"
                                + " WHERE ledger_account_id = ? ORDER BY account_sequence", id);
                long running = 0;
                for (int i = 0; i < chain.size(); i++) {
                    Map<String, Object> p = chain.get(i);
                    assertThat(p.get("account_sequence")).isEqualTo((long) i + 1);
                    long amt = (Long) p.get("amount_minor");
                    boolean increases = p.get("direction").equals(a.getNormalBalance().name());
                    running += increases ? amt : -amt;
                    assertThat(p.get("balance_after_minor")).isEqualTo(running);
                }
            }
            // Assets + Expenses = Liabilities + Equity + Revenue over this closed set of accounts.
            assertThat(accountingEquation).isZero();

            assertThat(f.reconciliation.isConsistent()).isTrue();
            assertThat(f.reconciliation.conservationOfMoney())
                    .allSatisfy(p -> assertThat(p.netSignedMinor()).isZero());
        }
    }
}
