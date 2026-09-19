package com.wbank.payment;

import com.wbank.account.funds.FundsReservation;
import com.wbank.ledger.domain.Posting;
import com.wbank.payment.domain.Payment;
import com.wbank.payment.domain.PaymentEvent;
import com.wbank.payment.domain.PaymentInstruction;
import com.wbank.payment.domain.PaymentSettlement;
import java.util.List;

/**
 * Everything needed to reconstruct a payment: the request, the current state, every step
 * taken, the hold, the settlement and the ledger legs it produced.
 */
public record PaymentView(PaymentInstruction instruction, Payment payment, List<PaymentEvent> events,
                          FundsReservation reservation, PaymentSettlement settlement,
                          List<Posting> settlementPostings, List<Posting> reversalPostings, boolean replayed) {}
