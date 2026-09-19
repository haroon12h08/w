package com.wbank.obligation.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The point-in-time visibility rule, in isolation. */
class LoanHistoryFoldTest {

    private final ObjectMapper json = new ObjectMapper();
    private final UUID loan = UUID.randomUUID();
    private final Instant jan1 = Instant.parse("2030-01-01T09:00:00Z");
    private final Instant jun1 = Instant.parse("2030-06-01T09:00:00Z");

    private LoanHistoryFold.Event e(int seq, LendingEventType type, Instant effective, Instant recorded, String payload)
            throws Exception {
        return new LoanHistoryFold.Event(UUID.randomUUID(), seq, seq, type, effective, recorded, json.readTree(payload),
                null, "t");
    }

    private List<LoanHistoryFold.Event> history() throws Exception {
        return List.of(
                e(1, LendingEventType.APPLICATION_RECEIVED, jan1, jan1, """
                        {"obligationNumber":"L1","debtorPartyId":"%s","productCode":"TERM_LOAN","currency":"USD",
                         "principalMinor":1000,"annualRateBps":0,"installmentCount":1}""".formatted(UUID.randomUUID())),
                e(2, LendingEventType.CREDIT_DECISION, jan1, jan1, "{\"decision\":\"APPROVED\"}"),
                e(3, LendingEventType.DEFAULT_DECLARED, jun1, jun1, "{\"decision\":\"DEFAULTED\"}"));
    }

    @Test
    void anEventIsVisibleOnlyOnceItHasBothHappenedAndBeenRecorded() throws Exception {
        var ev = e(9, LendingEventType.LOAN_SETTLED, jan1, jun1, "{}"); // effective Jan, known only in June
        assertThat(LoanHistoryFold.visible(ev, jun1, jun1)).isTrue();
        assertThat(LoanHistoryFold.visible(ev, jan1, jan1)).isFalse();  // not known then
        assertThat(LoanHistoryFold.visible(ev, jan1, jun1)).isTrue();   // hindsight view of January
        assertThat(LoanHistoryFold.visible(ev, jan1.minusSeconds(1), jun1)).isFalse(); // had not happened
    }

    @Test
    void aJanuaryViewNeverContainsTheJuneDefault() throws Exception {
        HistoricalLoanState jan = LoanHistoryFold.fold(loan, history(), jan1, jan1);
        assertThat(jan.status()).isEqualTo("APPROVED");
        assertThat(jan.decisions()).extracting(HistoricalLoanState.Decision::type).containsExactly("CREDIT_DECISION");
        assertThat(jan.eventsNotYetVisible()).isEqualTo(1);
        assertThat(LoanHistoryFold.fold(loan, history(), jun1, jun1).status()).isEqualTo("DEFAULTED");
    }

    @Test
    void orderComesFromTheSequenceNotFromInputOrder() throws Exception {
        List<LoanHistoryFold.Event> shuffled = new java.util.ArrayList<>(history());
        java.util.Collections.reverse(shuffled);
        assertThat(LoanHistoryFold.fold(loan, shuffled, jun1, jun1).status()).isEqualTo("DEFAULTED");
        assertThat(LoanHistoryFold.fold(loan, shuffled, jun1, jun1).lastLoanSeq()).isEqualTo(3);
    }
}
