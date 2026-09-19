package com.wbank.ledger.api;

import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.Posting;
import java.util.List;
import java.util.UUID;

/** The ledger's representation of an accepted journal, as returned by every module's API. */
public record PostedEntryResponse(
        UUID journalEntryId,
        long entryNumber,
        String entryType,
        String currencyCode,
        long totalAmountMinor,
        String status,
        String description,
        boolean replayed,
        List<PostingResponse> postings) {

    public record PostingResponse(UUID id, int entryLeg, UUID ledgerAccountId, String direction, long amountMinor,
                                  long balanceAfterMinor, long accountSequence) {
        public static PostingResponse from(Posting p) {
            return new PostingResponse(p.getId(), p.getEntryLeg(), p.getLedgerAccountId(), p.getDirection().name(),
                    p.getAmountMinor(), p.getBalanceAfterMinor(), p.getAccountSequence());
        }
    }

    public static PostedEntryResponse from(PostedEntry pe) {
        return new PostedEntryResponse(pe.entry().getId(), pe.entry().getEntryNumber(),
                pe.entry().getEntryType().name(), pe.entry().getCurrencyCode(), pe.entry().getTotalAmountMinor(),
                pe.entry().getStatus().name(), pe.entry().getDescription(), pe.replayed(),
                pe.postings().stream().map(PostingResponse::from).toList());
    }
}
