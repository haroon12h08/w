package com.wbank.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.wbank.obligation.history.DecisionFacts;
import com.wbank.platform.money.CurrencyRegistry;
import org.springframework.stereotype.Component;

/**
 * Decision-time facts, read ONLY from the decision snapshot (captured and hashed when the
 * decision was made). The one other input is the currency's scale, which is immutable
 * reference data (its code and scale are guarded by trigger since V6).
 *
 * <p>No loan status, balance, event or outcome that exists today is consulted.
 */
@Component
public class SnapshotDecisionFacts implements DecisionFactsSource {

    private final CurrencyRegistry currencies;

    public SnapshotDecisionFacts(CurrencyRegistry currencies) {
        this.currencies = currencies;
    }

    @Override
    public DecisionFacts facts(SourceDecision source) {
        JsonNode s = source.snapshot();
        String currency = s.at("/application/currency").asText();
        boolean anyDefaulted = false;
        long maxDpd = 0;
        for (JsonNode o : s.path("existingObligations")) {
            anyDefaulted |= "DEFAULTED".equals(o.path("status").asText());
            maxDpd = Math.max(maxDpd, o.path("daysPastDue").asLong());
        }
        Long settlementAvailable = null;
        String settlementId = s.at("/application/settlementAccountId").asText(null);
        for (JsonNode a : s.path("accounts")) {
            if (a.path("accountId").asText().equals(settlementId) && a.hasNonNull("availableMinor")) {
                settlementAvailable = a.get("availableMinor").asLong();
            }
        }
        return new DecisionFacts(s.at("/subject/customerStatus").asText(null), currency,
                currencies.require(currency).minorUnit(), s.at("/application/principalMinor").asLong(),
                s.at("/application/installmentCount").asLong(), s.at("/application/annualRateBps").asLong(),
                anyDefaulted, maxDpd, settlementAvailable, null);
    }
}
