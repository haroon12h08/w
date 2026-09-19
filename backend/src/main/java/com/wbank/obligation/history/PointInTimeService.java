package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.obligation.persistence.ObligationRepository;
import com.wbank.platform.error.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point-in-time queries: entity + timestamp in, the state as it was known then out.
 *
 * <p>Reads ONLY {@code lending_event}. The mutable current tables (obligation status,
 * balances) are never consulted, so later changes to them cannot leak into the past.
 * The one exception is locating which loans belong to a debtor: {@code obligation.debtor_party_id}
 * is immutable from the moment the application exists, and visibility is still decided by
 * the history (a loan whose application was not yet recorded at {@code knownAt} is omitted).
 */
@Service
public class PointInTimeService {

    private final LendingEventRepository events;
    private final ObligationRepository obligations;
    private final ObjectMapper json;

    public PointInTimeService(LendingEventRepository events, ObligationRepository obligations, ObjectMapper json) {
        this.events = events;
        this.obligations = obligations;
        this.json = json;
    }

    /** The loan at {@code asOf}, as known at {@code knownAt} (defaults to {@code asOf}: "as the bank knew then"). */
    @Transactional(readOnly = true)
    public HistoricalLoanState loanAt(UUID obligationId, Instant asOf, Instant knownAt) {
        List<LoanHistoryFold.Event> history = history(obligationId);
        if (history.isEmpty()) {
            throw NotFoundException.of("obligation", obligationId);
        }
        return LoanHistoryFold.fold(obligationId, history, asOf, knownAt == null ? asOf : knownAt);
    }

    /** All of a debtor's loans that the bank knew about at {@code knownAt}, as they stood at {@code asOf}. */
    @Transactional(readOnly = true)
    public List<HistoricalLoanState> borrowerAt(UUID debtorPartyId, Instant asOf, Instant knownAt) {
        Instant k = knownAt == null ? asOf : knownAt;
        return obligations.findByDebtorPartyIdOrderByProposedAtAsc(debtorPartyId).stream()
                .map(o -> LoanHistoryFold.fold(o.getId(), history(o.getId()), asOf, k))
                .filter(HistoricalLoanState::known)
                .toList();
    }

    /** The loan's history as known at {@code knownAt} (all of it if null), in recorded order. */
    @Transactional(readOnly = true)
    public List<LendingEvent> events(UUID obligationId, Instant knownAt) {
        return events.findByObligationIdOrderByLoanSeqAsc(obligationId).stream()
                .filter(e -> knownAt == null || !e.getRecordedAt().isAfter(knownAt))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoanHistoryFold.Event> history(UUID obligationId) {
        return events.findByObligationIdOrderByLoanSeqAsc(obligationId).stream().map(this::toFold).toList();
    }

    LoanHistoryFold.Event toFold(LendingEvent e) {
        try {
            return new LoanHistoryFold.Event(e.getId(), e.getLoanSeq(), e.getGlobalSeq(), e.getEventType(),
                    e.getEffectiveAt(), e.getRecordedAt(), json.readTree(e.getPayload()), e.getCorrectsEventId(),
                    e.getActor());
        } catch (Exception ex) {
            throw new IllegalStateException("Unreadable lending event " + e.getId(), ex);
        }
    }
}
