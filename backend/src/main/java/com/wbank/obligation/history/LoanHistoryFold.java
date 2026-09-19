package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbank.obligation.domain.Delinquency;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Point-in-time reconstruction of one loan: a pure function of its event history.
 *
 * <p>An event is visible at (asOf, knownAt) iff {@code effectiveAt <= asOf} AND
 * {@code recordedAt <= knownAt}. The second condition is what prevents leakage: an accrual
 * that belongs to February but was only booked in March is invisible to a February view
 * "as the bank knew it then". Events are applied in {@code loanSeq} order, which is the
 * order they were recorded (gap-free, enforced by PostgreSQL).
 *
 * <p>Deliberately free of I/O: the same function serves the application, the HTTP API and
 * the migration regression test.
 */
public final class LoanHistoryFold {

    /** An event as the fold needs it (decoupled from JPA). */
    public record Event(UUID id, int loanSeq, long globalSeq, LendingEventType type, Instant effectiveAt,
                        Instant recordedAt, JsonNode payload, UUID correctsEventId, String actor) {}

    private LoanHistoryFold() {}

    public static boolean visible(Event e, Instant asOf, Instant knownAt) {
        return !e.effectiveAt().isAfter(asOf) && !e.recordedAt().isAfter(knownAt);
    }

    public static HistoricalLoanState fold(UUID obligationId, List<Event> history, Instant asOf, Instant knownAt) {
        List<Event> ordered = history.stream().sorted(Comparator.comparingInt(Event::loanSeq)).toList();
        List<Event> seen = ordered.stream().filter(e -> visible(e, asOf, knownAt)).toList();

        String number = null;
        UUID debtor = null;
        String product = null;
        String currency = null;
        long principal = 0;
        int rate = 0;
        int count = 0;
        String allocationPolicy = null;
        String recognition = null;
        String status = null;
        Instant disbursedAt = null;
        LocalDate start = null;
        LocalDate maturity = null;
        Map<Integer, long[]> inst = new LinkedHashMap<>(); // seq -> [pDue, iDue, pPaid, iPaid, waived, accrued]
        Map<Integer, LocalDate> due = new LinkedHashMap<>();
        List<HistoricalLoanState.Decision> decisions = new ArrayList<>();
        List<HistoricalLoanState.Repayment> repayments = new ArrayList<>();
        List<HistoricalLoanState.Correction> corrections = new ArrayList<>();
        long pRepaid = 0;
        long iRepaid = 0;
        long accrued = 0;
        long waived = 0;
        HistoricalLoanState.Observation observed = null;
        int lastSeq = 0;

        for (Event e : seen) {
            JsonNode p = e.payload();
            lastSeq = e.loanSeq();
            if (e.type().impliedStatus() != null) {
                status = e.type().impliedStatus();
            }
            switch (e.type()) {
                case APPLICATION_RECEIVED -> {
                    number = text(p, "obligationNumber");
                    debtor = UUID.fromString(text(p, "debtorPartyId"));
                    product = text(p, "productCode");
                    currency = text(p, "currency");
                    principal = p.path("principalMinor").asLong();
                    rate = p.path("annualRateBps").asInt();
                    count = p.path("installmentCount").asInt();
                    allocationPolicy = text(p, "allocationPolicy");
                    recognition = text(p, "interestRecognition");
                }
                case CREDIT_DECISION, DEFAULT_DECLARED -> {
                    if (e.type() == LendingEventType.CREDIT_DECISION) {
                        status = text(p, "decision");
                    }
                    decisions.add(new HistoricalLoanState.Decision(e.id(), e.loanSeq(), e.type().name(),
                            text(p, "decision"), e.effectiveAt(), uuid(p, "decisionId"), text(p, "policyCode"),
                            p.hasNonNull("policyVersion") ? p.get("policyVersion").asInt() : null,
                            uuid(p, "snapshotId"), e.actor(), p.get("evidence")));
                }
                case LOAN_DISBURSED -> {
                    disbursedAt = e.effectiveAt();
                    start = date(p, "startDate");
                }
                case SCHEDULE_ESTABLISHED -> {
                    maturity = date(p, "maturityDate");
                    for (JsonNode i : p.path("installments")) {
                        int s = i.get("sequence").asInt();
                        inst.put(s, new long[] {i.get("principalMinor").asLong(), i.get("interestMinor").asLong(),
                                0, 0, 0, 0});
                        due.put(s, LocalDate.parse(i.get("dueDate").asText()));
                    }
                }
                case INTEREST_ACCRUED -> {
                    accrued += p.get("amountMinor").asLong();
                    long[] row = inst.get(p.get("installmentSequence").asInt());
                    if (row != null) {
                        row[5] = 1;
                    }
                }
                case REPAYMENT_RECEIVED -> {
                    pRepaid += p.get("principalMinor").asLong();
                    iRepaid += p.get("interestMinor").asLong();
                    for (JsonNode a : p.path("allocations")) {
                        long[] row = inst.get(a.get("sequence").asInt());
                        row[2] += a.get("principalMinor").asLong();
                        row[3] += a.get("interestMinor").asLong();
                    }
                    for (JsonNode w : p.path("waivers")) {
                        long[] row = inst.get(w.get("sequence").asInt());
                        row[4] += w.get("interestMinor").asLong();
                        waived += w.get("interestMinor").asLong();
                    }
                    repayments.add(new HistoricalLoanState.Repayment(e.id(), e.effectiveAt(),
                            p.get("amountMinor").asLong(), p.get("principalMinor").asLong(),
                            p.get("interestMinor").asLong()));
                }
                case DELINQUENCY_CHANGED -> observed = new HistoricalLoanState.Observation(date(p, "asOf"),
                        text(p, "toBucket"), p.path("daysPastDue").asLong(), e.recordedAt());
                case EVENT_CORRECTED -> corrections.add(new HistoricalLoanState.Correction(e.id(),
                        e.correctsEventId(), text(p, "field"), p.get("previousValue"), p.get("correctedValue"),
                        text(p, "reason"), e.recordedAt()));
                case APPLICATION_CANCELLED, LOAN_SETTLED -> {
                    // status only
                }
            }
        }

        List<HistoricalLoanState.Installment> schedule = new ArrayList<>();
        List<Delinquency.Line> lines = new ArrayList<>();
        for (var entry : inst.entrySet()) {
            long[] r = entry.getValue();
            LocalDate d = due.get(entry.getKey());
            schedule.add(new HistoricalLoanState.Installment(entry.getKey(), d, r[0], r[1], r[2], r[3], r[4], r[5] == 1));
            lines.add(new Delinquency.Line(entry.getKey(), d, r[0], r[1], r[2], r[3], r[4]));
        }
        boolean serviced = "ACTIVE".equals(status) || "DEFAULTED".equals(status);
        Delinquency.Status derived = serviced
                ? Delinquency.evaluate(lines, LocalDate.ofInstant(asOf, ZoneOffset.UTC)) : null;
        long outstanding = disbursedAt == null ? 0 : principal - pRepaid;

        return new HistoricalLoanState(obligationId, asOf, knownAt, number != null, number, debtor, product, currency,
                principal, rate, count, allocationPolicy, recognition, status, disbursedAt, start, maturity,
                List.copyOf(schedule), List.copyOf(decisions), List.copyOf(repayments), pRepaid, iRepaid, accrued,
                waived, outstanding, accrued - iRepaid, derived, observed, List.copyOf(corrections), seen.size(),
                ordered.size() - seen.size(), lastSeq);
    }

    private static String text(JsonNode p, String field) {
        JsonNode n = p.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static UUID uuid(JsonNode p, String field) {
        String t = text(p, field);
        return t == null ? null : UUID.fromString(t);
    }

    private static LocalDate date(JsonNode p, String field) {
        String t = text(p, field);
        return t == null ? null : LocalDate.parse(t);
    }
}
