package com.wbank.ledger.domain;

import com.wbank.platform.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A requested posting, before it is accepted by the ledger.
 *
 * <p>Separate from {@link Posting} because a request has no id, no sequence number and
 * no running balance: those are facts the ledger establishes, not inputs a caller may supply.
 */
public record PostingInstruction(UUID ledgerAccountId, PostingDirection direction, Money amount) {

    public PostingInstruction {
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Posting amount must be strictly positive: " + amount);
        }
    }

    public static PostingInstruction debit(UUID ledgerAccountId, Money amount) {
        return new PostingInstruction(ledgerAccountId, PostingDirection.DEBIT, amount);
    }

    public static PostingInstruction credit(UUID ledgerAccountId, Money amount) {
        return new PostingInstruction(ledgerAccountId, PostingDirection.CREDIT, amount);
    }

    public PostingInstruction mirrored() {
        return new PostingInstruction(ledgerAccountId, direction.opposite(), amount);
    }

    public long signedAmountMinor() {
        return direction.rawSignum() * amount.minorUnits();
    }
}
