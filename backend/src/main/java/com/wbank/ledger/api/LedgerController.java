package com.wbank.ledger.api;

import com.wbank.ledger.JournalService;
import com.wbank.ledger.LedgerReconciliationService;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.domain.JournalEntry;
import com.wbank.ledger.domain.JournalEntryRequest;
import com.wbank.ledger.domain.JournalEntryType;
import com.wbank.ledger.domain.LedgerAccount;
import com.wbank.ledger.domain.LedgerAccountBalance;
import com.wbank.ledger.domain.LedgerAccountPurpose;
import com.wbank.ledger.domain.LedgerAccountType;
import com.wbank.ledger.domain.Posting;
import com.wbank.ledger.domain.PostingDirection;
import com.wbank.ledger.domain.PostingInstruction;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal HTTP surface for exercising the ledger directly.
 *
 * <p>There is intentionally no endpoint that sets, adjusts or overwrites a balance.
 * The only way to change a balance is to post a balanced journal (or reverse one).
 */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final LedgerService ledgerService;
    private final JournalService journalService;
    private final LedgerReconciliationService reconciliationService;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public LedgerController(LedgerService ledgerService,
                            JournalService journalService,
                            LedgerReconciliationService reconciliationService,
                            CurrencyRegistry currencies,
                            Clock clock) {
        this.ledgerService = ledgerService;
        this.journalService = journalService;
        this.reconciliationService = reconciliationService;
        this.currencies = currencies;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ DTOs

    public record OpenAccountRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull LedgerAccountType accountType,
            @NotBlank String currency,
            @NotNull LedgerAccountPurpose purpose) {}

    public record PostingLine(
            @NotNull UUID ledgerAccountId,
            @NotNull PostingDirection direction,
            @NotBlank String amount) {}

    public record PostJournalRequest(
            @NotBlank String description,
            LocalDate valueDate,
            @NotBlank String currency,
            @NotEmpty List<@Valid @NotNull PostingLine> postings) {}

    public record LedgerAccountResponse(
            UUID id, String code, String name, String accountType, String normalBalance,
            String currencyCode, String purpose, String status, Instant createdAt) {
        public static LedgerAccountResponse from(LedgerAccount a) {
            return new LedgerAccountResponse(a.getId(), a.getCode(), a.getName(), a.getAccountType().name(),
                    a.getNormalBalance().name(), a.getCurrencyCode(), a.getPurpose().name(),
                    a.getStatus().name(), a.getCreatedAt());
        }
    }

    /**
     * {@code balanceMinor} is the transactionally maintained projection;
     * {@code ledgerDerivedBalanceMinor} is recomputed from postings on this request.
     * They must always be equal; exposing both makes that claim checkable by a client.
     */
    public record LedgerAccountBalanceResponse(
            UUID ledgerAccountId, String currencyCode, String normalBalance,
            long balanceMinor, String balance, long ledgerDerivedBalanceMinor,
            long totalDebitsMinor, long totalCreditsMinor, long postingCount,
            Long minBalanceMinor, Instant lastPostedAt) {}

    public record ReversalRequest(@NotBlank String reason) {}

    public record ReconciliationReportResponse(
            boolean consistent,
            List<LedgerReconciliationService.CurrencyPosition> currencyPositions,
            List<LedgerReconciliationService.BalanceDiscrepancy> balanceDiscrepancies,
            List<LedgerReconciliationService.EntryImbalance> entryImbalances) {}

    // ------------------------------------------------------------- endpoints

    @PostMapping("/accounts")
    public ResponseEntity<LedgerAccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest req) {
        LedgerAccount account = journalService.openInternalAccount(
                req.code().strip(), req.name().strip(), req.accountType(),
                currencies.require(req.currency()), req.purpose());
        return ResponseEntity.status(HttpStatus.CREATED).body(LedgerAccountResponse.from(account));
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<LedgerAccountResponse> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(LedgerAccountResponse.from(ledgerService.requireAccount(id)));
    }

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<LedgerAccountBalanceResponse> getBalance(@PathVariable UUID id) {
        LedgerAccount account = ledgerService.requireAccount(id);
        LedgerAccountBalance b = ledgerService.requireBalance(id);
        CurrencyUnit currency = currencies.require(b.getCurrencyCode());
        return ResponseEntity.ok(new LedgerAccountBalanceResponse(
                id, b.getCurrencyCode(), account.getNormalBalance().name(),
                b.getBalanceMinor(), b.balance(currency).toDecimal().toPlainString(),
                reconciliationService.derivedBalanceMinor(id),
                b.getTotalDebitsMinor(), b.getTotalCreditsMinor(), b.getPostingCount(),
                b.getMinBalanceMinor(), b.getLastPostedAt()));
    }

    /**
     * Posts a balanced ADJUSTMENT journal. An {@code Idempotency-Key} header is required:
     * a ledger write reachable over an unreliable network must be safely retryable.
     * A replay returns 200 with the original entry; a first post returns 201.
     */
    @PostMapping("/entries")
    public ResponseEntity<PostedEntryResponse> postEntry(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody PostJournalRequest req) {
        CurrencyUnit currency = currencies.require(req.currency());
        List<PostingInstruction> legs = req.postings().stream()
                .map(line -> new PostingInstruction(line.ledgerAccountId(), line.direction(),
                        MoneyParser.parse(line.amount(), currency)))
                .toList();
        JournalEntryRequest request = new JournalEntryRequest(
                JournalEntryType.ADJUSTMENT,
                req.description().strip(),
                req.valueDate() != null ? req.valueDate() : LocalDate.now(clock),
                currency,
                legs,
                idempotencyKey,
                RequestContext.forOperation("ledger.post_journal"));

        PostedEntry posted = journalService.post(request);
        return ResponseEntity.status(posted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, Boolean.toString(posted.replayed()))
                .body(PostedEntryResponse.from(posted));
    }

    @GetMapping("/entries/{id}")
    public ResponseEntity<PostedEntryResponse> getEntry(@PathVariable UUID id) {
        JournalEntry entry = ledgerService.findEntry(id)
                .orElseThrow(() -> NotFoundException.of("journal_entry", id));
        List<Posting> postings = ledgerService.postingsOf(id);
        return ResponseEntity.ok(PostedEntryResponse.from(new PostedEntry(entry, postings)));
    }

    @PostMapping("/entries/{id}/reversal")
    public ResponseEntity<PostedEntryResponse> reverseEntry(@PathVariable UUID id,
                                                            @Valid @RequestBody ReversalRequest req) {
        PostedEntry reversal = journalService.reverse(
                id, req.reason().strip(), RequestContext.forOperation("ledger.entry_reversal"));
        return ResponseEntity.status(HttpStatus.CREATED).body(PostedEntryResponse.from(reversal));
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<ReconciliationReportResponse> getReconciliation() {
        return ResponseEntity.ok(new ReconciliationReportResponse(
                reconciliationService.isConsistent(),
                reconciliationService.conservationOfMoney(),
                reconciliationService.balanceDiscrepancies(),
                reconciliationService.entryImbalances()));
    }
}
