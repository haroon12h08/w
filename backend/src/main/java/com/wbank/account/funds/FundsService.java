package com.wbank.account.funds;

import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.ledger.LedgerService;
import com.wbank.ledger.domain.LedgerAccountBalance;
import com.wbank.platform.error.BusinessRuleViolationException;
import com.wbank.platform.error.InsufficientFundsException;
import com.wbank.platform.error.NotFoundException;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account-level funds control: the three balances, and holds.
 *
 * <ul>
 *   <li><b>Ledger balance</b>: what has actually been booked (the ledger's projection).</li>
 *   <li><b>Reserved</b>: the sum of ACTIVE holds; money promised but not yet moved.</li>
 *   <li><b>Available</b> = ledger balance − reserved − floor: what may still be committed.</li>
 * </ul>
 *
 * Every decision that relies on "available" is taken while holding the ledger's
 * balance-row lock for the account, i.e. the same lock every posting takes. Reserving and
 * posting to one account therefore serialise; two concurrent authorisations cannot both
 * see the same available amount. PostgreSQL re-checks available ≥ 0 at every commit.
 */
@Service
public class FundsService {

    private final FundsReservationRepository reservations;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final CurrencyRegistry currencies;
    private final Clock clock;

    public FundsService(FundsReservationRepository reservations, AccountService accounts, LedgerService ledger,
                        CurrencyRegistry currencies, Clock clock) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.ledger = ledger;
        this.currencies = currencies;
        this.clock = clock;
    }

    /** @param availableMinor null for an account without a floor (unbounded) */
    public record Balances(UUID accountId, String currency, long ledgerBalanceMinor, long reservedMinor,
                           Long floorMinor, Long availableMinor) {}

    @Transactional(readOnly = true)
    public Balances balances(UUID accountId) {
        Account account = accounts.require(accountId);
        if (!account.hasLedgerPosition()) {
            return new Balances(accountId, account.getCurrencyCode(), 0, 0, null, 0L);
        }
        return compute(account, ledger.requireBalance(account.getLedgerAccountId()));
    }

    /** Places a hold after checking, under the balance lock, that the funds are available. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FundsReservation reserve(Account account, Money amount, String purpose, UUID referenceId, Instant expiresAt) {
        requireAvailable(account, amount);
        return reservations.save(FundsReservation.hold(account.getId(), account.getLedgerAccountId(),
                account.getCurrencyCode(), amount.minorUnits(), purpose, referenceId, clock.instant(), expiresAt));
    }

    /**
     * Refuses a debit that would spend money already held for something else. Called by
     * every debit path of a customer account before it posts.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Balances requireAvailable(Account account, Money amount) {
        if (!account.hasLedgerPosition()) {
            throw new BusinessRuleViolationException("account.no_ledger_position",
                    "Account " + account.getAccountNumber() + " is not active");
        }
        LedgerAccountBalance locked = ledger.lockBalance(account.getLedgerAccountId());
        Balances b = compute(account, locked);
        if (b.availableMinor() != null && b.availableMinor() < amount.minorUnits()) {
            CurrencyUnit currency = currencies.require(account.getCurrencyCode());
            throw new InsufficientFundsException(account.getLedgerAccountId(),
                    Money.ofMinorUnits(Math.max(b.availableMinor(), 0), currency), amount);
        }
        return b;
    }

    /**
     * The account's balances read under the balance-row lock, without deciding anything.
     * Callers that must <em>record</em> a refusal (rather than abort) use this, because an
     * exception thrown through a transactional boundary would roll the whole unit back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Balances lockedBalances(Account account) {
        return compute(account, ledger.lockBalance(account.getLedgerAccountId()));
    }

    /** The held money is being paid out now, in this transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FundsReservation consume(UUID reservationId) {
        return resolve(reservationId, ReservationStatus.CONSUMED);
    }

    /** The hold is no longer needed; the money becomes available again. */
    @Transactional(propagation = Propagation.MANDATORY)
    public FundsReservation release(UUID reservationId) {
        return resolve(reservationId, ReservationStatus.RELEASED);
    }

    public CurrencyUnit currencyOf(String code) {
        return currencies.require(code);
    }

    @Transactional(readOnly = true)
    public FundsReservation require(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> NotFoundException.of("funds_reservation", reservationId));
    }

    private FundsReservation resolve(UUID reservationId, ReservationStatus target) {
        FundsReservation r = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> NotFoundException.of("funds_reservation", reservationId));
        r.resolve(target, clock.instant());
        return reservations.save(r);
    }

    private Balances compute(Account account, LedgerAccountBalance balance) {
        long reserved = reservations.activeTotal(account.getLedgerAccountId());
        Long floor = balance.getMinBalanceMinor();
        Long available = floor == null ? null : balance.getBalanceMinor() - reserved - floor;
        return new Balances(account.getId(), account.getCurrencyCode(), balance.getBalanceMinor(), reserved, floor,
                available);
    }
}
