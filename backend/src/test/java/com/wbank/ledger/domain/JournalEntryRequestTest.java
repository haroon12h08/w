package com.wbank.ledger.domain;

import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.UnbalancedEntryException;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JournalEntryRequestTest {

    private CurrencyUnit usd;
    private CurrencyUnit eur;
    private OperationContext context;
    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void setUp() {
        usd = new CurrencyUnit("USD", 2);
        eur = new CurrencyUnit("EUR", 2);
        context = RequestContext.forOperation("test.entry");
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
    }

    @Test
    @DisplayName("Valid balanced entry request is accepted")
    void testValidBalancedEntry() {
        Money amount = Money.ofMinorUnits(5000, usd); // $50.00
        List<PostingInstruction> postings = List.of(
                PostingInstruction.debit(accountA, amount),
                PostingInstruction.credit(accountB, amount)
        );

        JournalEntryRequest request = new JournalEntryRequest(
                JournalEntryType.CUSTOMER_TRANSFER,
                "Transfer $50",
                LocalDate.now(),
                usd,
                postings,
                "idempotency-123",
                context
        );

        assertEquals(usd, request.currency());
        assertEquals(2, request.postings().size());
        assertEquals(amount, request.total());
    }

    @Test
    @DisplayName("Unbalanced entry request throws UnbalancedEntryException")
    void testUnbalancedEntryThrows() {
        Money amount1 = Money.ofMinorUnits(5000, usd);
        Money amount2 = Money.ofMinorUnits(4000, usd); // $40.00 - unbalanced by $10.00!

        List<PostingInstruction> postings = List.of(
                PostingInstruction.debit(accountA, amount1),
                PostingInstruction.credit(accountB, amount2)
        );

        assertThrows(UnbalancedEntryException.class, () -> new JournalEntryRequest(
                JournalEntryType.CUSTOMER_TRANSFER,
                "Unbalanced entry",
                LocalDate.now(),
                usd,
                postings,
                null,
                context
        ));
    }

    @Test
    @DisplayName("Entry request with fewer than two postings throws UnbalancedEntryException")
    void testSinglePostingThrows() {
        Money amount = Money.ofMinorUnits(5000, usd);
        List<PostingInstruction> postings = List.of(PostingInstruction.debit(accountA, amount));

        assertThrows(UnbalancedEntryException.class, () -> new JournalEntryRequest(
                JournalEntryType.CUSTOMER_TRANSFER,
                "Single posting",
                LocalDate.now(),
                usd,
                postings,
                null,
                context
        ));
    }

    @Test
    @DisplayName("Entry request with mismatched currency in postings throws CurrencyMismatchException")
    void testMismatchedCurrencyThrows() {
        Money usdAmount = Money.ofMinorUnits(5000, usd);
        Money eurAmount = Money.ofMinorUnits(5000, eur);

        List<PostingInstruction> postings = List.of(
                PostingInstruction.debit(accountA, usdAmount),
                PostingInstruction.credit(accountB, eurAmount)
        );

        assertThrows(CurrencyMismatchException.class, () -> new JournalEntryRequest(
                JournalEntryType.CUSTOMER_TRANSFER,
                "Mismatched currency",
                LocalDate.now(),
                usd,
                postings,
                null,
                context
        ));
    }
}
