package com.wbank.payment.persistence;

import com.wbank.payment.domain.PaymentSettlement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentSettlementRepository extends JpaRepository<PaymentSettlement, UUID> {

    Optional<PaymentSettlement> findByPaymentId(UUID paymentId);
}
