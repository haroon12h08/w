package com.wbank.payment;

import com.wbank.account.domain.Account;
import com.wbank.support.DomainFixtures;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class PaymentFixtures {

    final PaymentService payments;
    final DomainFixtures d;

    PaymentFixtures(PaymentService payments, DomainFixtures d) {
        this.payments = payments;
        this.d = d;
    }

    PaymentView pay(Account debtor, Account creditor, long minor, String key, boolean execute) {
        return payments.submit(new PaymentProcessor.SubmitCommand(key, debtor.getId(), creditor.getId(),
                d.money(minor, debtor.getCurrencyCode()), "invoice 42"), execute);
    }

    PaymentView pay(Account debtor, Account creditor, long minor) {
        return pay(debtor, creditor, minor, "pay-" + UUID.randomUUID(), true);
    }
}
