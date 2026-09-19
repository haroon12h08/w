package com.wbank.obligation;

import com.wbank.obligation.persistence.ObligationRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * End-of-day servicing across the loan book: interest accrual and delinquency evaluation
 * for every disbursed, unsettled loan. Each loan is serviced in its own transaction, so one
 * failing loan does not block the rest, and a re-run is harmless (accruals are once per
 * instalment; delinquency events are written only on a bucket change).
 */
@Service
public class LoanServicingService {

    private static final Logger log = LoggerFactory.getLogger(LoanServicingService.class);

    private final ObligationRepository obligations;
    private final LoanService loans;

    public LoanServicingService(ObligationRepository obligations, LoanService loans) {
        this.obligations = obligations;
        this.loans = loans;
    }

    public record RunSummary(LocalDate asOf, int loansServiced, int accrualsPosted, int bucketChanges, int failures) {}

    public RunSummary run(LocalDate asOf) {
        int serviced = 0;
        int accrued = 0;
        int changes = 0;
        int failures = 0;
        List<java.util.UUID> ids = new ArrayList<>(obligations.findServicedIds());
        for (var id : ids) {
            try {
                LoanService.ServicingResult r = loans.service(id, asOf);
                serviced++;
                accrued += r.accrualsPosted();
                changes += r.bucketChanged() ? 1 : 0;
            } catch (IllegalArgumentException e) {
                throw e; // a bad as-of date is the caller's error, not a per-loan failure
            } catch (RuntimeException e) {
                failures++;
                log.error("Servicing of loan {} as of {} failed: {}", id, asOf, e.getMessage());
            }
        }
        return new RunSummary(asOf, serviced, accrued, changes, failures);
    }
}
