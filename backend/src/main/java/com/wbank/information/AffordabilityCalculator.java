package com.wbank.information;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AFFORDABILITY_V1: a deterministic, documented calculation over the financial facts visible at
 * a point in time. Not a score and not a prediction.
 *
 * <h2>Definitions (all amounts integer minor units of the loan currency, per month)</h2>
 * <ol>
 *   <li><b>Monthly normalisation.</b> MONTHLY amounts as recorded. ANNUAL income is divided by 12
 *       and rounded DOWN (never overstate income); ANNUAL obligations are divided by 12 and
 *       rounded UP (never understate obligations).</li>
 *   <li><b>Income.</b> Current ACTIVE income observations whose {@code appliesFrom} has arrived.
 *       VERIFIED and DECLARED income are summed separately and never mixed silently. Income that
 *       applies only in the future is listed separately and excluded. Income state: VERIFIED
 *       (any verified), DECLARED_ONLY, or NONE.</li>
 *   <li><b>External recurring obligations.</b> Current ACTIVE obligation observations that apply
 *       at the as-of date. They are KNOWN_COMPLETE only if an ACTIVE obligation disclosure is
 *       visible ("these are all of them"); otherwise PARTIALLY_KNOWN (some recorded) or UNKNOWN.
 *       An unknown total is never replaced by zero.</li>
 *   <li><b>Internal obligations.</b> The level instalment of each of the party's ACTIVE or
 *       DEFAULTED loans with this bank (from its own records).</li>
 *   <li><b>Proposed payment.</b> The level instalment of the loan being assessed.</li>
 *   <li><b>Existing debt service</b> = external + internal; <b>total debt service</b> = existing +
 *       proposed.</li>
 *   <li><b>Debt-service ratio (basis points)</b> = ceil(total × 10 000 / income), exact integer
 *       arithmetic, rounded UP; <b>residual monthly capacity</b> = income − total (may be negative).</li>
 *   <li>Two bases, reported side by side: VERIFIED_INCOME (verified income only) and
 *       DECLARED_INCLUSIVE (verified + declared). A basis is INDETERMINATE, with reasons, when its
 *       income is absent or zero, when external obligations are not KNOWN_COMPLETE, or when a
 *       relevant fact is in another currency (no conversion is attempted).</li>
 *   <li><b>Completeness</b> for this calculation: COMPLETE when verified income and a complete
 *       obligation disclosure are both available (and no currency conflict); INSUFFICIENT when no
 *       income of any provenance is known; otherwise PARTIAL. Not a risk measure.</li>
 * </ol>
 *
 * <p>Pure: the output is a function of {@link Input} only. The protected methods are the
 * calculation's individual decisions; mutation tests override them one at a time to prove
 * that each wrong choice is detected.
 */
public class AffordabilityCalculator {

    public static final String VERSION = "AFFORDABILITY_V1";

    public record InternalObligation(UUID obligationId, String obligationNumber, String status,
                                     long monthlyInstalmentMinor) {}

    public record Input(String calculationVersion, Instant asOf, Instant knownAt, String currency,
                        long proposedMonthlyPaymentMinor, List<FactView> facts,
                        List<InternalObligation> internalObligations) {}

    // ------------------------------------------------------------ decisions (hooks)

    protected boolean isVerified(FactView f) {
        return "VERIFIED".equals(f.provenance());
    }

    protected boolean appliesAt(FactView f, LocalDate asOfDate) {
        return f.appliesFrom() == null || !f.appliesFrom().isAfter(asOfDate);
    }

    protected long monthlyIncome(FactView f) {
        return "ANNUAL".equals(f.frequency()) ? Math.floorDiv(f.amountMinor(), 12) : f.amountMinor();
    }

    protected long monthlyObligation(FactView f) {
        return "ANNUAL".equals(f.frequency()) ? -Math.floorDiv(-f.amountMinor(), 12) : f.amountMinor();
    }

    /** Income to use when none of the required provenance exists: none (the basis is then indeterminate). */
    protected Long incomeWhenAbsent() {
        return null;
    }

    /** External obligations when they are not known to be complete: unknown, never zero. */
    protected Long externalWhenNotComplete(long recordedExternal) {
        return null;
    }

    protected long ratioBps(long total, long income) {
        BigInteger[] qr = BigInteger.valueOf(total).multiply(BigInteger.valueOf(10_000))
                .divideAndRemainder(BigInteger.valueOf(income));
        return qr[1].signum() > 0 ? qr[0].longValueExact() + 1 : qr[0].longValueExact();
    }

    // -------------------------------------------------------------------- compute

    public Map<String, Object> compute(Input in) {
        LocalDate asOfDate = LocalDate.ofInstant(in.asOf(), ZoneOffset.UTC);
        long verified = 0;
        long declared = 0;
        boolean anyVerified = false;
        boolean anyDeclared = false;
        boolean incomeCurrencyConflict = false;
        boolean obligationCurrencyConflict = false;
        List<Map<String, Object>> incomeComponents = new ArrayList<>();
        List<Map<String, Object>> futureIncome = new ArrayList<>();
        List<Map<String, Object>> externalComponents = new ArrayList<>();
        List<Map<String, Object>> futureObligations = new ArrayList<>();
        List<Map<String, Object>> employment = new ArrayList<>();
        long recordedExternal = 0;
        boolean anyExternalRecorded = false;
        Map<String, Object> disclosure = null;

        for (FactView f : in.facts()) {
            boolean active = "ACTIVE".equals(f.status());
            switch (f.kind()) {
                case "INCOME" -> {
                    if (!active) {
                        continue;
                    }
                    if (!in.currency().equals(f.currency())) {
                        incomeCurrencyConflict = true;
                        incomeComponents.add(component(f, null, "EXCLUDED_CURRENCY_MISMATCH"));
                        continue;
                    }
                    long monthly = monthlyIncome(f);
                    if (!appliesAt(f, asOfDate)) {
                        futureIncome.add(component(f, monthly, "EXCLUDED_APPLIES_IN_FUTURE"));
                        continue;
                    }
                    if (isVerified(f)) {
                        verified = Math.addExact(verified, monthly);
                        anyVerified = true;
                    } else {
                        declared = Math.addExact(declared, monthly);
                        anyDeclared = true;
                    }
                    incomeComponents.add(component(f, monthly, isVerified(f) ? "VERIFIED" : "DECLARED"));
                }
                case "RECURRING_OBLIGATION" -> {
                    if (!active) {
                        continue;
                    }
                    if (!in.currency().equals(f.currency())) {
                        obligationCurrencyConflict = true;
                        externalComponents.add(component(f, null, "EXCLUDED_CURRENCY_MISMATCH"));
                        continue;
                    }
                    long monthly = monthlyObligation(f);
                    if (!appliesAt(f, asOfDate)) {
                        futureObligations.add(component(f, monthly, "EXCLUDED_APPLIES_IN_FUTURE"));
                        continue;
                    }
                    recordedExternal = Math.addExact(recordedExternal, monthly);
                    anyExternalRecorded = true;
                    Map<String, Object> c = component(f, monthly, isVerified(f) ? "VERIFIED" : "DECLARED");
                    c.put("outstandingMinor", f.outstandingMinor());
                    externalComponents.add(c);
                }
                case "OBLIGATION_DISCLOSURE" -> {
                    if (active) {
                        disclosure = new LinkedHashMap<>();
                        disclosure.put("factId", f.id().toString());
                        disclosure.put("provenance", isVerified(f) ? "VERIFIED" : "DECLARED");
                        disclosure.put("effectiveAt", f.effectiveAt().toString());
                    }
                }
                case "EMPLOYMENT" -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("factId", f.id().toString());
                    e.put("status", f.type());
                    e.put("observationStatus", f.status());
                    e.put("provenance", f.provenance());
                    e.put("source", f.source());
                    e.put("startDate", f.employmentStartDate() == null ? null : f.employmentStartDate().toString());
                    Long months = f.employmentStartDate() == null || f.employmentStartDate().isAfter(asOfDate) ? null
                            : ChronoUnit.MONTHS.between(f.employmentStartDate(), asOfDate);
                    e.put("monthsSinceStartAtAsOf", months);
                    e.put("monthsSinceStartProvenance", months == null ? "UNKNOWN" : "DERIVED");
                    employment.add(e);
                }
                default -> throw new IllegalArgumentException("Unknown fact kind " + f.kind());
            }
        }

        long internal = in.internalObligations().stream().mapToLong(InternalObligation::monthlyInstalmentMinor).sum();
        String obligationsState = disclosure != null ? "KNOWN_COMPLETE" : anyExternalRecorded ? "PARTIALLY_KNOWN" : "UNKNOWN";
        Long external = "KNOWN_COMPLETE".equals(obligationsState) ? Long.valueOf(recordedExternal)
                : externalWhenNotComplete(recordedExternal);
        String incomeState = anyVerified ? "VERIFIED" : anyDeclared ? "DECLARED_ONLY" : "NONE";

        Map<String, Object> income = new LinkedHashMap<>();
        income.put("state", incomeState);
        income.put("verifiedMonthlyMinor", anyVerified ? Long.valueOf(verified) : incomeWhenAbsent());
        income.put("declaredMonthlyMinor", anyDeclared ? Long.valueOf(declared) : null);
        income.put("components", incomeComponents);
        income.put("futureIncomeExcluded", futureIncome);
        income.put("currencyConflict", incomeCurrencyConflict);

        Map<String, Object> obligations = new LinkedHashMap<>();
        obligations.put("state", obligationsState);
        obligations.put("disclosure", disclosure);
        obligations.put("externalMonthlyMinor", external);
        obligations.put("recordedExternalMonthlyMinor", anyExternalRecorded ? recordedExternal : 0L);
        obligations.put("recordedExternalIsLowerBound", !"KNOWN_COMPLETE".equals(obligationsState));
        obligations.put("externalComponents", externalComponents);
        obligations.put("futureObligationsExcluded", futureObligations);
        obligations.put("internalMonthlyMinor", internal);
        obligations.put("internalComponents", in.internalObligations());
        obligations.put("currencyConflict", obligationCurrencyConflict);

        Long verifiedIncome = anyVerified ? Long.valueOf(verified) : incomeWhenAbsent();
        Long inclusiveIncome = anyVerified || anyDeclared ? Long.valueOf(verified + declared) : incomeWhenAbsent();
        Map<String, Object> bases = new LinkedHashMap<>();
        bases.put("VERIFIED_INCOME", basis(verifiedIncome, "NO_VERIFIED_INCOME", external, internal,
                in.proposedMonthlyPaymentMinor(), incomeCurrencyConflict, obligationCurrencyConflict));
        bases.put("DECLARED_INCLUSIVE", basis(inclusiveIncome, "NO_INCOME", external, internal,
                in.proposedMonthlyPaymentMinor(), incomeCurrencyConflict, obligationCurrencyConflict));

        List<Map<String, Object>> requirements = List.of(
                requirement("VERIFIED_INCOME", anyVerified && verified > 0 && !incomeCurrencyConflict, incomeState),
                requirement("OBLIGATIONS_KNOWN_COMPLETE", "KNOWN_COMPLETE".equals(obligationsState)
                        && !obligationCurrencyConflict, obligationsState));
        boolean allMet = requirements.stream().allMatch(r -> (Boolean) r.get("met"));
        String completeness = allMet ? "COMPLETE" : "NONE".equals(incomeState) ? "INSUFFICIENT" : "PARTIAL";
        Map<String, Object> completenessMap = new LinkedHashMap<>();
        completenessMap.put("status", completeness);
        completenessMap.put("requirements", requirements);
        completenessMap.put("missing", requirements.stream().filter(r -> !(Boolean) r.get("met")).map(r -> r.get("requirement")).toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calculationVersion", VERSION);
        out.put("asOf", in.asOf().toString());
        out.put("knownAt", in.knownAt().toString());
        out.put("currency", in.currency());
        out.put("income", income);
        out.put("obligations", obligations);
        out.put("proposedMonthlyPaymentMinor", in.proposedMonthlyPaymentMinor());
        out.put("bases", bases);
        out.put("completeness", completenessMap);
        out.put("employment", employment);
        return out;
    }

    private Map<String, Object> basis(Long income, String noIncomeReason, Long external, long internal, long proposed,
                                      boolean incomeConflict, boolean obligationConflict) {
        List<String> reasons = new ArrayList<>();
        if (income == null) {
            reasons.add(noIncomeReason);
        } else if (income <= 0) {
            reasons.add("ZERO_INCOME");
        }
        if (external == null) {
            reasons.add("OBLIGATIONS_NOT_KNOWN_COMPLETE");
        }
        if (incomeConflict || obligationConflict) {
            reasons.add("CURRENCY_MISMATCH");
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", reasons.isEmpty() ? "DETERMINATE" : "INDETERMINATE");
        b.put("reasons", reasons);
        b.put("incomeMonthlyMinor", income);
        Long existing = external == null ? null : external + internal;
        Long total = existing == null ? null : existing + proposed;
        b.put("existingDebtServiceMinor", existing);
        b.put("totalDebtServiceMinor", total);
        b.put("debtServiceRatioBps", reasons.isEmpty() ? ratioBps(total, income) : null);
        b.put("residualMonthlyCapacityMinor", reasons.isEmpty() ? income - total : null);
        return b;
    }

    private static Map<String, Object> requirement(String name, boolean met, String observed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requirement", name);
        r.put("met", met);
        r.put("observed", observed);
        return r;
    }

    private Map<String, Object> component(FactView f, Long monthly, String treatment) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("factId", f.id().toString());
        c.put("seriesId", f.seriesId().toString());
        c.put("type", f.type());
        c.put("provenance", f.provenance());
        c.put("source", f.source());
        c.put("amountMinor", f.amountMinor());
        c.put("currency", f.currency());
        c.put("frequency", f.frequency());
        c.put("appliesFrom", f.appliesFrom() == null ? null : f.appliesFrom().toString());
        c.put("monthlyMinor", monthly);
        c.put("treatment", treatment);
        return c;
    }
}
