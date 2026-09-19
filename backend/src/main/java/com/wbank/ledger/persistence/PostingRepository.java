package com.wbank.ledger.persistence;

import com.wbank.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostingRepository extends JpaRepository<Posting, UUID> {

    List<Posting> findByJournalEntryIdOrderByEntryLegAsc(UUID journalEntryId);

    Slice<Posting> findByLedgerAccountIdOrderByAccountSequenceDesc(UUID ledgerAccountId, Pageable pageable);

    long countByLedgerAccountId(UUID ledgerAccountId);
}
