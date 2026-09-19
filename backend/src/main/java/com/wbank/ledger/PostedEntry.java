package com.wbank.ledger;

import com.wbank.ledger.domain.JournalEntry;
import com.wbank.ledger.domain.Posting;
import java.util.List;

/**
 * The result of accepting a journal entry: the entry and the postings it produced.
 *
 * @param replayed true when no new financial effect was created because the request's
 *                 idempotency key had already been posted; the original entry is returned
 */
public record PostedEntry(JournalEntry entry, List<Posting> postings, boolean replayed) {

    public PostedEntry {
        postings = List.copyOf(postings);
    }

    public PostedEntry(JournalEntry entry, List<Posting> postings) {
        this(entry, postings, false);
    }
}
