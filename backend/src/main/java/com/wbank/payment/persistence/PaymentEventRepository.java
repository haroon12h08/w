package com.wbank.payment.persistence;

import com.wbank.payment.domain.PaymentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByPaymentIdOrderBySequenceNoAsc(UUID paymentId);

    long countByPaymentId(UUID paymentId);
}
