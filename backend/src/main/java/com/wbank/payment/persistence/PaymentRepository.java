package com.wbank.payment.persistence;

import com.wbank.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByInstructionId(UUID instructionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT p.id FROM Payment p WHERE p.status = com.wbank.payment.domain.PaymentStatus.AUTHORIZED"
            + " AND p.expiresAt <= :asOf ORDER BY p.expiresAt")
    List<UUID> findAuthorizedExpiringBy(@Param("asOf") Instant asOf);
}
