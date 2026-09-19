package com.wbank.ledger.domain;

import com.wbank.platform.context.OperationContext;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.UnbalancedEntryException;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A proposed journal entry.
 *
 * <p>Validation of the double-entry identity happens here, in the domain, before any
 * database work: an unbalanced entry is not a persistence failure, it is a nonsensical
 * financial statement. PostgreSQL re-checks the same identity at commit time as a
 * backstop against any path that bypasses this type.
 *
 * @param idempotencyKey optional; when present the ledger guarantees at most one entry
 *                       is ever created for it (unique index on {@code journal_entry})
 */
public record JournalEntryRequest(
        JournalEntryType entryType,
        String description,
        LocalDate valueDate,
        CurrencyUnit currency,
        List<PostingInstruction> postings,
        String idempotencyKey,
        OperationContext context) {

    /** Upper bound on legs: keeps entry_leg within SMALLINT and rejects pathological payloads. */
    public static final int MAX_POSTINGS = 100;

    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 200;

    public JournalEntryRequest {
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(context, "context");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A journal entry must carry a description");
        }
        if (idempotencyKey != null) {
            idempotencyKey = idempotencyKey.strip();
            if (idempotencyKey.isEmpty() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
                throw new IllegalArgumentException(
                        "An idempotency key must be 1.." + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
            }
        }
        postings = List.copyOf(Objects.requireNonNull(postings, "postings"));

        if (postings.size() < 2) {
            throw new UnbalancedEntryException(
                    "A journal entry requires at least two postings; received " + postings.size());
        }
        if (postings.size() > MAX_POSTINGS) {
            throw new IllegalArgumentException(
                    "A journal entry may carry at most %d postings; received %d".formatted(MAX_POSTINGS, postings.size()));
        }

        long net = 0L;
        long debitTotal = 0L;
        for (PostingInstruction posting : postings) {
            if (!posting.amount().currency().code().equals(currency.code())) {
                throw new CurrencyMismatchException(
                        "Posting to %s is denominated in %s but the entry is in %s"
                                .formatted(posting.ledgerAccountId(), posting.amount().currency().code(),
                                        currency.code()));
            }
            try {
                net = Math.addExact(net, posting.signedAmountMinor());
                if (posting.direction() == PostingDirection.DEBIT) {
                    debitTotal = Math.addExact(debitTotal, posting.amount().minorUnits());
                }
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Journal entry totals exceed the representable range");
            }
        }

        if (net != 0L) {
            throw new UnbalancedEntryException(
                    "Debits and credits differ by %d minor units of %s".formatted(net, currency.code()));
        }
        if (debitTotal == 0L) {
            throw new UnbalancedEntryException("A journal entry must move a non-zero amount");
        }
    }

    /** The entry's magnitude: the total debited, which by the identity equals the total credited. */
    public Money total() {
        long debitTotal = postings.stream()
                .filter(p -> p.direction() == PostingDirection.DEBIT)
                .mapToLong(p -> p.amount().minorUnits())
                .reduce(0L, Math::addExact);
        return Money.ofMinorUnits(debitTotal, currency);
    }

    /**
     * A stable SHA-256 over everything that determines the entry's financial meaning.
     *
     * <p>Used to tell a genuine retry (same key, same request) apart from a client bug
     * that reuses a key for a different request. Leg order is part of the fingerprint
     * because it is part of the recorded entry. Provenance (actor, correlation id) is
     * deliberately excluded: a retry may legitimately arrive with a new correlation id.
     */
    public String fingerprint() {
        StringBuilder canonical = new StringBuilder()
                .append("v1|").append(entryType.name())
                .append('|').append(currency.code())
                .append('|').append(valueDate)
                .append('|').append(description.length()).append(':').append(description);
        for (PostingInstruction p : postings) {
            canonical.append('|').append(p.ledgerAccountId())
                    .append(':').append(p.direction().name())
                    .append(':').append(p.amount().minorUnits());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
