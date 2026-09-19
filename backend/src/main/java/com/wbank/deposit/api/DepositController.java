package com.wbank.deposit.api;

import com.wbank.account.AccountService;
import com.wbank.deposit.DepositService;
import com.wbank.ledger.PostedEntry;
import com.wbank.ledger.api.PostedEntryResponse;
import com.wbank.platform.money.CurrencyRegistry;
import com.wbank.platform.money.MoneyParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Cash in / cash out at the counter. Money moves only through {@link DepositService}. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}")
public class DepositController {

    private final DepositService deposits;
    private final AccountService accounts;
    private final CurrencyRegistry currencies;

    public DepositController(DepositService deposits, AccountService accounts, CurrencyRegistry currencies) {
        this.deposits = deposits;
        this.accounts = accounts;
        this.currencies = currencies;
    }

    public record CashRequest(@NotBlank String amount, String description) {}

    @PostMapping("/cash-deposits")
    public ResponseEntity<PostedEntryResponse> deposit(@PathVariable UUID accountId,
                                                       @RequestHeader("Idempotency-Key") String key,
                                                       @Valid @RequestBody CashRequest req) {
        var currency = currencies.require(accounts.require(accountId).getCurrencyCode());
        return respond(deposits.depositCash(accountId, MoneyParser.parsePositive(req.amount(), currency),
                req.description(), key));
    }

    @PostMapping("/cash-withdrawals")
    public ResponseEntity<PostedEntryResponse> withdraw(@PathVariable UUID accountId,
                                                        @RequestHeader("Idempotency-Key") String key,
                                                        @Valid @RequestBody CashRequest req) {
        var currency = currencies.require(accounts.require(accountId).getCurrencyCode());
        return respond(deposits.withdrawCash(accountId, MoneyParser.parsePositive(req.amount(), currency),
                req.description(), key));
    }

    private static ResponseEntity<PostedEntryResponse> respond(PostedEntry posted) {
        return ResponseEntity.status(posted.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(PostedEntryResponse.from(posted));
    }
}
