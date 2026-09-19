package com.wbank.account.api;

import com.wbank.account.AccountService;
import com.wbank.account.domain.Account;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.CurrencyUnit;
import com.wbank.platform.money.Money;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account lifecycle. The balance shown is always the ledger's. */
@RestController
@RequestMapping("/api/v1")
public class AccountController {

    private final AccountService accounts;
    private final CurrencyRegistry currencies;

    public AccountController(AccountService accounts, CurrencyRegistry currencies) {
        this.accounts = accounts;
        this.currencies = currencies;
    }

    public record OpenAccountRequest(@NotNull UUID customerId, @NotBlank String productCode,
                                     @NotBlank String currency, String overdraftLimit) {}

    public record AccountResponse(UUID id, String accountNumber, UUID customerId, UUID ownerPartyId,
                                  String productCode, String productFamily, String currency, String status,
                                  long overdraftLimitMinor, UUID ledgerAccountId, Long ledgerBalanceMinor,
                                  String ledgerBalance, Instant openedAt, Instant activatedAt, Instant closedAt) {
        static AccountResponse from(Account a, Money ledgerBalance) {
            return new AccountResponse(a.getId(), a.getAccountNumber(), a.getCustomerId(), a.getOwnerPartyId(),
                    a.getProductCode(), a.getProductFamily().name(), a.getCurrencyCode(), a.getStatus().name(),
                    a.getOverdraftLimitMinor(), a.getLedgerAccountId(),
                    ledgerBalance == null ? null : ledgerBalance.minorUnits(),
                    ledgerBalance == null ? null : ledgerBalance.toDecimal().toPlainString(),
                    a.getOpenedAt(), a.getActivatedAt(), a.getClosedAt());
        }
    }

    @PostMapping("/accounts")
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest req) {
        CurrencyUnit currency = currencies.require(req.currency());
        Money overdraft = req.overdraftLimit() == null ? Money.zero(currency)
                : MoneyParser.parse(req.overdraftLimit(), currency);
        Account account = accounts.openDepositAccount(req.customerId(), req.productCode(), currency, overdraft);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(account.getId()));
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return view(id);
    }

    @GetMapping("/customers/{customerId}/accounts")
    public List<AccountResponse> byCustomer(@PathVariable UUID customerId) {
        return accounts.listByCustomer(customerId).stream().map(a -> view(a.getId())).toList();
    }

    @PostMapping("/accounts/{id}/activate")
    public AccountResponse activate(@PathVariable UUID id) {
        accounts.activate(id);
        return view(id);
    }

    @PostMapping("/accounts/{id}/freeze")
    public AccountResponse freeze(@PathVariable UUID id) {
        accounts.freeze(id);
        return view(id);
    }

    @PostMapping("/accounts/{id}/unfreeze")
    public AccountResponse unfreeze(@PathVariable UUID id) {
        accounts.unfreeze(id);
        return view(id);
    }

    @PostMapping("/accounts/{id}/close")
    public AccountResponse close(@PathVariable UUID id) {
        accounts.close(id);
        return view(id);
    }

    private AccountResponse view(UUID id) {
        AccountService.AccountPosition p = accounts.position(id);
        return AccountResponse.from(p.account(), p.account().hasLedgerPosition() ? p.ledgerBalance() : null);
    }
}
