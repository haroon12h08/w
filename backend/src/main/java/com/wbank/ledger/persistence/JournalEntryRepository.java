package com.wbank.ledger.persistence;

import com.wbank.ledger.domain.JournalEntry;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    List<JournalEntry> findByCorrelationIdOrderByEntryNumberAsc(UUID correlationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM JournalEntry e WHERE e.id = :id")
    Optional<JournalEntry> findByIdForUpdate(@Param("id") UUID id);
}
