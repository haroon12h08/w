package com.wbank.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;

import com.wbank.account.domain.Account;
import com.wbank.account.funds.FundsService;
import com.wbank.account.funds.ReservationStatus;
import com.wbank.payment.domain.PaymentSettlement;
import com.wbank.payment.domain.PaymentStatus;
import com.wbank.payment.persistence.PaymentSettlementRepository;
import com.wbank.support.DomainFixtures;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 8. Failure DURING processing: the fault is injected after the ledger journal has been
 * written inside the execution transaction. The transaction must roll back completely
 * (no posting survives), and the failure must still be recorded, with the hold released.
 */
class PaymentFailureInjectionTest extends PostgresIntegrationTest {

    @MockitoSpyBean PaymentSettlementRepository settlements;
    @Autowired PaymentFixtures p;
    @Autowired DomainFixtures d;
    @Autowired LedgerFixtures f;
    @Autowired FundsService funds;

    @Test
    void faultAfterTheLedgerPostingLeavesNoFinancialEffect() {
        Account alice = d.activeAccount("USD");
        Account bob = d.activeAccount("USD");
        d.fund(alice.getId(), 5_000, "USD");
        long entriesBefore = f.count("SELECT count(*) FROM journal_entry");

        doThrow(new IllegalStateException("simulated crash after posting"))
                .when(settlements).save(any(PaymentSettlement.class));
        PaymentView v;
        try {
            v = p.pay(alice, bob, 1_200);
        } finally {
            reset(settlements);
        }

        assertThat(v.payment().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(v.payment().getReasonCode()).isEqualTo("PROCESSING_ERROR");
        assertThat(v.reservation().getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(v.settlement()).isNull();
        assertThat(f.count("SELECT count(*) FROM journal_entry")).isEqualTo(entriesBefore);
        assertThat(f.count("SELECT count(*) FROM journal_entry WHERE idempotency_key = ?",
                "payment.settlement:" + v.payment().getId())).isZero();
        assertThat(d.ledgerBalance(alice.getId())).isEqualTo(5_000);
        assertThat(d.ledgerBalance(bob.getId())).isZero();
        assertThat(funds.balances(alice.getId()).availableMinor()).isEqualTo(5_000);
        assertThat(v.events()).extracting(e -> e.getEventType()).endsWith("AUTHORIZED", "EXECUTION_FAILED");

        // a new instruction goes through normally afterwards
        assertThat(p.pay(alice, bob, 1_200).payment().getStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(f.reconciliation.isConsistent()).isTrue();
    }
}
