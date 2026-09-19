package com.wbank.ledger;

import com.wbank.ledger.domain.JournalEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountBalance;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountStatus;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.Posting;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.ledger.persistence.JournalEntryRepository;
import com.wbank.ledger.persistence.LedgerAccountBalanceRepository;
import com.wbank.ledger.persistence.LedgerAccountRepository;
import com.wbank.ledger.persistence.PostingRepository;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.ConflictException;
import com.wbank.platform.error.CurrencyMismatchException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component in the system permitted to write to the ledger.
 *
 * <p>Every other module — deposits, payments, and every future module such as lending
 * or cards — expresses what it wants as a balanced {@link JournalEntryRequest} and asks
 * this service to post it. That single write path is what makes the invariants
 * enforceable at all: there is no second way for money to move.
 *
 * <p>Ordering of work inside {@link #post}:
 * <ol>
 *   <li>domain validation (already done by {@code JournalEntryRequest}'s constructor)</li>
 *   <li>lock every affected balance row, in ascending account-id order</li>
 *   <li>check account operability, currency, and balance floors</li>
 *   <li>write the entry, then the postings, then update the projections</li>
 *   <li>write the audit record</li>
 * </ol>
 * Steps 2–5 share one database transaction; nothing is partially applied.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerAccountBalanceRepository balances;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final CurrencyRegistry currencies;
    private final AuditTrail auditTrail;
    private final Clock clock;
    private final JdbcTemplate jdbc;

    public LedgerService(LedgerAccountRepository accounts,
                         LedgerAccountBalanceRepository balances,
                         JournalEntryRepository entries,
                         PostingRepository postings,
                         CurrencyRegistry currencies,
                         AuditTrail auditTrail,
                         Clock clock,
                         JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.balances = balances;
        this.entries = entries;
        this.postings = postings;
        this.currencies = currencies;
        this.auditTrail = auditTrail;
        this.clock = clock;
        this.jdbc = jdbc;
    }

    // -----------------------------------------------------------------
    // Chart of accounts
    // -----------------------------------------------------------------

    /**
     * Opens a ledger account together with its balance projection row.
     *
     * <p>The two are created atomically and only here, so no posting can ever encounter
     * an account without a balance row.
     *
     * @param minBalanceMinor authorised floor; {@code null} for unconstrained internal accounts
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount openAccount(String code,
                                     String name,
                                     LedgerAccountType type,
                                     CurrencyUnit currency,
                                     LedgerAccountPurpose purpose,
                                     Long minBalanceMinor) {
        if (accounts.existsByCode(code)) {
            throw new ConflictException("ledger_account.duplicate_code",
                    "Ledger account code already in use: " + code);
        }
        Instant now = clock.instant();
        LedgerAccount account = accounts.save(
                LedgerAccount.open(UUID.randomUUID(), code, name, type, currency.code(), purpose, now));
        balances.save(LedgerAccountBalance.openingZero(account.getId(), currency.code(), minBalanceMinor, now));
        return account;
    }

    /**
     * Changes whether a ledger account accepts postings.
     *
     * <p>Takes the same balance-row lock as {@link #post}, so a status change and a
     * posting to the same account serialise: a close cannot slip in between a posting's
     * balance check and its write, and a close observes the final balance. Closing
     * requires a zero balance; a closed account never reopens (also enforced by trigger).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount changeAccountStatus(UUID ledgerAccountId, LedgerAccountStatus target) {
        LedgerAccountBalance balance = balances.findByIdForUpdate(ledgerAccountId)
                .orElseThrow(() -> NotFoundException.of("ledger_account_balance", ledgerAccountId));
        LedgerAccount account = requireAccountInternal(ledgerAccountId);
        if (target == LedgerAccountStatus.CLOSED && balance.getBalanceMinor() != 0) {
            throw new BusinessRuleViolationException("ledger.close_non_zero_balance",
                    "Ledger account %s has balance %d and cannot be closed"
                            .formatted(ledgerAccountId, balance.getBalanceMinor()));
        }
        if (account.getStatus() == LedgerAccountStatus.CLOSED) {
            throw new BusinessRuleViolationException("ledger.account_closed",
                    "Ledger account " + ledgerAccountId + " is closed");
        }
        account.changeStatus(target, clock.instant());
        return accounts.save(account);
    }

    /**
     * Takes the account's balance-row lock (the same lock {@link #post} takes) and returns
     * the locked projection. Lets modules outside the ledger make a read-decide-write
     * decision about an account (e.g. reserving funds) that cannot race a posting.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccountBalance lockBalance(UUID ledgerAccountId) {
        return balances.findByIdForUpdate(ledgerAccountId)
                .orElseThrow(() -> NotFoundException.of("ledger_account_balance", ledgerAccountId));
    }

    @Transactional(readOnly = true)
    public LedgerAccount requireAccount(UUID id) {
        return accounts.findById(id).orElseThrow(() -> NotFoundException.of("ledger_account", id));
    }

    @Transactional(readOnly = true)
    public LedgerAccount requireAccountByCode(String code) {
        return accounts.findByCode(code).orElseThrow(() -> new NotFoundException(
                "ledger_account.not_found", "Ledger account with code " + code + " does not exist"));
    }

    @Transactional(readOnly = true)
    public LedgerAccountBalance requireBalance(UUID ledgerAccountId) {
        return balances.findById(ledgerAccountId)
                .orElseThrow(() -> NotFoundException.of("ledger_account_balance", ledgerAccountId));
    }

    @Transactional(readOnly = true)
    public Optional<JournalEntry> findEntry(UUID id) {
        return entries.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Posting> postingsOf(UUID journalEntryId) {
        return postings.findByJournalEntryIdOrderByEntryLegAsc(journalEntryId);
    }

    // -----------------------------------------------------------------
    // Posting
    // -----------------------------------------------------------------

    /**
     * Accepts a balanced journal entry and applies it.
     *
     * <p>Requires an existing transaction: posting money is never the outermost unit of
     * work. The calling use case (a transfer, a deposit) owns the transaction boundary,
     * so its own bookkeeping commits with the ledger movement or not at all.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry post(JournalEntryRequest request) {
        String fingerprint = null;
        if (request.idempotencyKey() != null) {
            fingerprint = request.fingerprint();
            // Serialise every transaction carrying this key. The lock is released at
            // COMMIT/ROLLBACK, so a waiting duplicate proceeds only once the first
            // attempt's outcome is durable, and under READ COMMITTED its next statement
            // sees that outcome. Hash collisions merely serialise unrelated keys.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
                    String.class, request.idempotencyKey());

            Optional<JournalEntry> existing = entries.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                JournalEntry entry = existing.get();
                if (!fingerprint.equals(entry.getRequestFingerprint())) {
                    throw new ConflictException("ledger.idempotency_key_reused",
                            "Idempotency key %s was already used for a different request (entry %s)"
                                    .formatted(request.idempotencyKey(), entry.getId()));
                }
                log.debug("Replaying journal entry {} for idempotency key {}", entry.getId(),
                        request.idempotencyKey());
                return new PostedEntry(entry,
                        postings.findByJournalEntryIdOrderByEntryLegAsc(entry.getId()), true);
            }
        }

        Instant now = clock.instant();

        // 1. Resolve accounts and collapse repeated references to the same account, so a
        //    single entry that touches one account twice still locks it exactly once.
        Map<UUID, LedgerAccount> involved = resolveAccounts(request);

        // 2. Lock in a globally deterministic order. Two concurrent transfers in opposite
        //    directions between the same pair of accounts therefore cannot deadlock.
        Map<UUID, LedgerAccountBalance> locked = lockBalancesInDeterministicOrder(involved.keySet());

        // 3. Admissibility checks, before anything is written.
        for (PostingInstruction instruction : request.postings()) {
            LedgerAccount account = involved.get(instruction.ledgerAccountId());
            LedgerAccountBalance balance = locked.get(instruction.ledgerAccountId());

            if (!account.getStatus().acceptsPostings()) {
                throw new BusinessRuleViolationException("ledger.account_not_postable",
                        "Ledger account %s is %s and cannot accept postings"
                                .formatted(account.getId(), account.getStatus()));
            }
            if (balance.wouldBreachFloor(account.getNormalBalance(), instruction.direction(),
                    instruction.amount().minorUnits())) {
                CurrencyUnit currency = currencies.require(account.getCurrencyCode());
                throw new InsufficientFundsException(
                        account.getId(), balance.available(currency), instruction.amount());
            }
        }

        // 4. Write the entry.
        JournalEntry entry;
        try {
            entry = entries.saveAndFlush(JournalEntry.post(
                    UUID.randomUUID(),
                    request.entryType(),
                    request.description(),
                    request.valueDate(),
                    request.total(),
                    null,
                    request.context(),
                    request.idempotencyKey(),
                    fingerprint,
                    now));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Two concurrent requests carrying the same idempotency key: the unique index
            // on journal_entry decided which one wins. The loser replays the winner.
            throw new ConflictException("ledger.concurrent_duplicate",
                    "A concurrent request already posted this entry", e);
        }

        List<Posting> written = applyPostings(request, entry, involved, locked, now);

        auditTrail.record("ledger.journal_entry.posted", "journal_entry", entry.getId(), request.context(),
                auditPayload(entry, written));

        return new PostedEntry(entry, written);
    }

    /**
     * Reverses a previously posted entry by writing its exact mirror.
     *
     * <p>Corrections are never made by editing or deleting. Both the mistake and its
     * remedy remain visible, which is the only way an auditor (or a later reasoning
     * system) can explain what the bank did and why.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry reverse(UUID entryId, String reason, OperationContext context) {
        // Row lock: two concurrent reversals of the same entry serialise here, and the
        // loser observes REVERSED instead of racing to the unique index.
        JournalEntry original = entries.findByIdForUpdate(entryId)
                .orElseThrow(() -> NotFoundException.of("journal_entry", entryId));

        if (original.isReversed()) {
            throw new ConflictException("ledger.already_reversed",
                    "Journal entry " + entryId + " has already been reversed");
        }
        if (original.getEntryType() == JournalEntryType.REVERSAL) {
            throw new BusinessRuleViolationException("ledger.reversal_not_reversible",
                    "A reversal entry cannot itself be reversed: " + entryId);
        }

        CurrencyUnit currency = currencies.require(original.getCurrencyCode());
        List<PostingInstruction> mirrored = postings.findByJournalEntryIdOrderByEntryLegAsc(entryId).stream()
                .map(p -> new PostingInstruction(
                        p.getLedgerAccountId(), p.getDirection().opposite(), p.amount(currency)))
                .toList();

        Instant now = clock.instant();
        Map<UUID, LedgerAccount> involved = new LinkedHashMap<>();
        for (PostingInstruction instruction : mirrored) {
            involved.computeIfAbsent(instruction.ledgerAccountId(), this::requireAccountInternal);
        }
        Map<UUID, LedgerAccountBalance> locked = lockBalancesInDeterministicOrder(involved.keySet());

        // A reversal must not be blocked by a balance floor it would itself repair, but it
        // must still respect floors on accounts it debits: money that has already left an
        // account cannot be clawed back into overdraft silently.
        for (PostingInstruction instruction : mirrored) {
            LedgerAccount account = involved.get(instruction.ledgerAccountId());
            LedgerAccountBalance balance = locked.get(instruction.ledgerAccountId());
            if (balance.wouldBreachFloor(account.getNormalBalance(), instruction.direction(),
                    instruction.amount().minorUnits())) {
                throw new InsufficientFundsException(
                        account.getId(), balance.available(currency), instruction.amount());
            }
        }

        long totalMinor = mirrored.stream()
                .filter(p -> p.direction() == com.wbank.ledger.domain.PostingDirection.DEBIT)
                .mapToLong(p -> p.amount().minorUnits())
                .reduce(0L, Math::addExact);

        JournalEntry reversal = entries.saveAndFlush(JournalEntry.post(
                UUID.randomUUID(),
                JournalEntryType.REVERSAL,
                "Reversal of entry %s: %s".formatted(entryId, reason),
                original.getValueDate(),
                Money.ofMinorUnits(totalMinor, currency),
                entryId,
                context,
                null,
                null,
                now));

        List<Posting> written = writePostings(mirrored, reversal, involved, locked, now);

        original.markReversedBy(reversal.getId());
        entries.save(original);

        auditTrail.record("ledger.journal_entry.reversed", "journal_entry", entryId, context,
                Map.of("reversalEntryId", reversal.getId().toString(),
                        "reason", reason,
                        "amountMinor", totalMinor,
                        "currency", currency.code()));

        return new PostedEntry(reversal, written);
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private Map<UUID, LedgerAccount> resolveAccounts(JournalEntryRequest request) {
        Map<UUID, LedgerAccount> involved = new LinkedHashMap<>();
        for (PostingInstruction instruction : request.postings()) {
            LedgerAccount account = involved.computeIfAbsent(
                    instruction.ledgerAccountId(), this::requireAccountInternal);
            if (!account.getCurrencyCode().equals(request.currency().code())) {
                throw new CurrencyMismatchException(
                        "Ledger account %s is denominated in %s but the entry is in %s"
                                .formatted(account.getId(), account.getCurrencyCode(), request.currency().code()));
            }
        }
        return involved;
    }

    private LedgerAccount requireAccountInternal(UUID id) {
        return accounts.findById(id).orElseThrow(() -> NotFoundException.of("ledger_account", id));
    }

    private Map<UUID, LedgerAccountBalance> lockBalancesInDeterministicOrder(Iterable<UUID> accountIds) {
        List<UUID> ordered = new ArrayList<>();
        accountIds.forEach(ordered::add);
        ordered.sort(Comparator.naturalOrder());

        Map<UUID, LedgerAccountBalance> locked = new HashMap<>();
        for (UUID id : ordered) {
            locked.put(id, balances.findByIdForUpdate(id)
                    .orElseThrow(() -> NotFoundException.of("ledger_account_balance", id)));
        }
        return locked;
    }

    private List<Posting> applyPostings(JournalEntryRequest request,
                                        JournalEntry entry,
                                        Map<UUID, LedgerAccount> involved,
                                        Map<UUID, LedgerAccountBalance> locked,
                                        Instant now) {
        return writePostings(request.postings(), entry, involved, locked, now);
    }

    private List<Posting> writePostings(List<PostingInstruction> instructions,
                                        JournalEntry entry,
                                        Map<UUID, LedgerAccount> involved,
                                        Map<UUID, LedgerAccountBalance> locked,
                                        Instant now) {
        List<Posting> written = new ArrayList<>(instructions.size());
        int leg = 0;
        for (PostingInstruction instruction : instructions) {
            LedgerAccount account = involved.get(instruction.ledgerAccountId());
            LedgerAccountBalance balance = locked.get(instruction.ledgerAccountId());

            long sequence = balance.nextSequence();
            balance.apply(account.getNormalBalance(), instruction.direction(),
                    instruction.amount().minorUnits(), now);

            written.add(postings.save(Posting.create(
                    UUID.randomUUID(),
                    entry.getId(),
                    leg++,
                    account.getId(),
                    instruction.amount(),
                    instruction.direction(),
                    sequence,
                    balance.getBalanceMinor(),
                    now)));
        }
        balances.saveAll(locked.values());
        return written;
    }

    private Map<String, Object> auditPayload(JournalEntry entry, List<Posting> written) {
        List<Map<String, Object>> legs = written.stream()
                .map(p -> Map.<String, Object>of(
                        "postingId", p.getId().toString(),
                        "ledgerAccountId", p.getLedgerAccountId().toString(),
                        "direction", p.getDirection().name(),
                        "amountMinor", p.getAmountMinor(),
                        "balanceAfterMinor", p.getBalanceAfterMinor(),
                        "accountSequence", p.getAccountSequence()))
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("journalEntryId", entry.getId().toString());
        payload.put("entryType", entry.getEntryType().name());
        payload.put("currency", entry.getCurrencyCode());
        payload.put("totalAmountMinor", entry.getTotalAmountMinor());
        payload.put("valueDate", entry.getValueDate().toString());
        payload.put("description", entry.getDescription());
        payload.put("idempotencyKey", entry.getIdempotencyKey());
        payload.put("postings", legs);
        return payload;
    }
}
