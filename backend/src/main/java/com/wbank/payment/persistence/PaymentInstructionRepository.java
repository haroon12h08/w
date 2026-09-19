package com.wbank.payment.persistence;

import com.wbank.payment.domain.PaymentInstruction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentInstructionRepository extends JpaRepository<PaymentInstruction, UUID> {

    Optional<PaymentInstruction> findByIdempotencyKey(String idempotencyKey);
}
