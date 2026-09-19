package com.wbank.ledger;

import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use-case boundary for operations that originate directly against the ledger
 * (rather than through a product module such as deposits).
 *
 * <p>{@link LedgerService} deliberately refuses to start its own transaction
 * ({@code Propagation.MANDATORY}): posting is never the outermost unit of work. This
 * class is that outermost unit for ledger-level callers, so each method here is exactly
 * one atomic database transaction — the whole journal commits, or nothing does.
 */
@Service
public class JournalService {

    private final LedgerService ledger;

    public JournalService(LedgerService ledger) {
        this.ledger = ledger;
    }

    /**
     * Opens an internal (bank-owned) ledger account. Customer deposit ledger accounts
     * are owned by the deposit module and cannot be opened here.
     */
    @Transactional
    public LedgerAccount openInternalAccount(String code, String name, LedgerAccountType type,
                                             CurrencyUnit currency, LedgerAccountPurpose purpose) {
        if (purpose == LedgerAccountPurpose.CUSTOMER_DEPOSIT) {
            throw new BusinessRuleViolationException("ledger.purpose_not_permitted",
                    "Customer deposit ledger accounts are opened by the deposit module");
        }
        return ledger.openAccount(code, name, type, currency, purpose, null);
    }

    /** Posts a manually originated journal. Only {@code ADJUSTMENT} entries may be posted this way. */
    @Transactional
    public PostedEntry post(JournalEntryRequest request) {
        if (request.entryType() != JournalEntryType.ADJUSTMENT) {
            throw new BusinessRuleViolationException("ledger.entry_type_not_permitted",
                    "Only ADJUSTMENT entries may be posted directly; %s is owned by a product module"
                            .formatted(request.entryType()));
        }
        for (var posting : request.postings()) {
            LedgerAccount account = ledger.requireAccount(posting.ledgerAccountId());
            if (account.getPurpose().isProductPosition()) {
                throw new BusinessRuleViolationException("ledger.product_position",
                        "Ledger account %s backs a %s position; it changes only through that product's operations"
                                .formatted(account.getId(), account.getPurpose()));
            }
        }
        return ledger.post(request);
    }

    /**
     * Reverses a manual journal. Entries produced by product operations (deposits,
     * transfers, loan events) carry domain state alongside them, so reversing only their
     * ledger half would desynchronise the two; those are corrected through their owning
     * module instead.
     */
    @Transactional
    public PostedEntry reverse(UUID entryId, String reason, OperationContext context) {
        var entry = ledger.findEntry(entryId).orElseThrow(() -> NotFoundException.of("journal_entry", entryId));
        if (entry.getEntryType() != JournalEntryType.ADJUSTMENT) {
            throw new BusinessRuleViolationException("ledger.entry_owned_by_product",
                    "Journal entry %s is a %s; it must be corrected through its owning module"
                            .formatted(entryId, entry.getEntryType()));
        }
        return ledger.reverse(entryId, reason, context);
    }
}
