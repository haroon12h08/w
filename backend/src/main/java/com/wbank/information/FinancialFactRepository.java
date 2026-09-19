package com.wbank.information;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FinancialFactRepository extends JpaRepository<FinancialFact, UUID> {

    List<FinancialFact> findByPartyIdOrderBySeqAsc(UUID partyId);

    List<FinancialFact> findBySeriesIdOrderBySeqAsc(UUID seriesId);
}
