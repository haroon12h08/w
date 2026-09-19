package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbank.obligation.domain.Delinquency;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A loan as it stood at {@code asOf}, according to what the bank knew at {@code knownAt}.
 * Built only from history events; nothing here comes from the current (mutable) tables.
 *
 * <p>Values are labelled by nature:
 * <ul>
 *   <li>facts and decisions: taken from events;</li>
 *   <li>{@code derivedDelinquency}: <b>derived</b> at {@code asOf} from the schedule and the
 *       repayments known, recomputable at any time;</li>
 *   <li>{@code lastObservedDelinquency}: what the bank had <b>observed</b> (materialised) by then.</li>
 * </ul>
 */
public record HistoricalLoanState(
        UUID obligationId, Instant asOf, Instant knownAt, boolean known,
        String obligationNumber, UUID debtorPartyId, String productCode, String currency, long principalMinor,
        int annualRateBps, int installmentCount, String allocationPolicy, String interestRecognition,
        String status, Instant disbursedAt, LocalDate startDate, LocalDate maturityDate,
        List<Installment> schedule, List<Decision> decisions, List<Repayment> repayments,
        long principalRepaidMinor, long interestRepaidMinor, long interestAccruedMinor, long interestWaivedMinor,
        long outstandingPrincipalMinor, long accruedUnpaidInterestMinor,
        Delinquency.Status derivedDelinquency, Observation lastObservedDelinquency,
        List<Correction> corrections, int eventsConsidered, int eventsNotYetVisible, int lastLoanSeq) {

    public record Installment(int sequence, LocalDate dueDate, long principalDueMinor, long interestDueMinor,
                              long principalPaidMinor, long interestPaidMinor, long interestWaivedMinor,
                              boolean interestAccrued) {}

    public record Decision(UUID eventId, int loanSeq, String type, String decision, Instant decidedAt,
                           UUID decisionId, String policyCode, Integer policyVersion, UUID snapshotId,
                           String decidedBy, JsonNode evidence) {}

    public record Repayment(UUID eventId, Instant receivedAt, long amountMinor, long principalMinor, long interestMinor) {}

    public record Observation(LocalDate asOf, String bucket, long daysPastDue, Instant recordedAt) {}

    public record Correction(UUID eventId, UUID correctsEventId, String field, JsonNode previousValue,
                             JsonNode correctedValue, String reason, Instant recordedAt) {}
}
