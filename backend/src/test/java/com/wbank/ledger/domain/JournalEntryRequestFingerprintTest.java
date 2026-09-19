package com.wbank.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wbank.platform.context.RequestContext;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalEntryRequestFingerprintTest {

    private final CurrencyUnit usd = new CurrencyUnit("USD", 2);
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private JournalEntryRequest req(long amount, String description, String key) {
        Money m = Money.ofMinorUnits(amount, usd);
        return new JournalEntryRequest(JournalEntryType.ADJUSTMENT, description, LocalDate.of(2026, 1, 1), usd,
                List.of(PostingInstruction.debit(a, m), PostingInstruction.credit(b, m)), key,
                RequestContext.forOperation("unit"));
    }

    @Test
    void fingerprintIsStableAcrossProvenanceButSensitiveToFinancialContent() {
        String base = req(100, "x", "k").fingerprint();
        assertThat(base).matches("[0-9a-f]{64}");
        assertThat(req(100, "x", "k").fingerprint()).isEqualTo(base); // new correlation id, same meaning
        assertThat(req(101, "x", "k").fingerprint()).isNotEqualTo(base);
        assertThat(req(100, "y", "k").fingerprint()).isNotEqualTo(base);
    }

    @Test
    void idempotencyKeyIsNormalisedAndBounded() {
        assertThat(req(1, "x", "  key-1 ").idempotencyKey()).isEqualTo("key-1");
        assertThatThrownBy(() -> req(1, "x", "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> req(1, "x", "k".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void numberOfLegsIsBounded() {
        List<PostingInstruction> legs = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            legs.add(PostingInstruction.debit(a, Money.ofMinorUnits(1, usd)));
            legs.add(PostingInstruction.credit(b, Money.ofMinorUnits(1, usd)));
        }
        assertThatThrownBy(() -> new JournalEntryRequest(JournalEntryType.ADJUSTMENT, "x", LocalDate.of(2026, 1, 1),
                usd, legs, null, RequestContext.forOperation("unit")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 100");
    }
}
