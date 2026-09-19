package com.wbank.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/** Party -> customer -> account -> cash -> loan, over HTTP, against PostgreSQL. */
@AutoConfigureMockMvc
class DomainApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode call(String path, String body, ResultMatcher expect, String... headers) throws Exception {
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body);
        for (int i = 0; i < headers.length; i += 2) {
            req = req.header(headers[i], headers[i + 1]);
        }
        String res = mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString();
        return res.isEmpty() ? null : json.readTree(res);
    }

    @Test
    void endToEnd() throws Exception {
        String party = call("/api/v1/parties/persons",
                "{\"givenName\":\"Grace\",\"familyName\":\"Hopper\",\"dateOfBirth\":\"1986-12-09\",\"countryCode\":\"US\"}",
                status().isCreated()).get("id").asText();
        String customer = call("/api/v1/customers", "{\"partyId\":\"" + party + "\"}", status().isCreated())
                .get("id").asText();
        mvc.perform(get("/api/v1/customers/" + customer)).andExpect(jsonPath("$.status").value("PENDING"));
        call("/api/v1/customers/" + customer + "/reactivate", null, status().isConflict());
        call("/api/v1/customers/" + customer + "/activate", null, status().isOk());

        JsonNode acct = call("/api/v1/accounts",
                "{\"customerId\":\"" + customer + "\",\"productCode\":\"CURRENT\",\"currency\":\"USD\"}",
                status().isCreated());
        String account = acct.get("id").asText();
        assertThat(acct.get("status").asText()).isEqualTo("PENDING");
        assertThat(acct.get("ledgerBalanceMinor").isNull()).isTrue();
        call("/api/v1/accounts/" + account + "/cash-deposits", "{\"amount\":\"10.00\"}",
                status().isUnprocessableEntity(), "Idempotency-Key", "x-" + UUID.randomUUID());
        call("/api/v1/accounts/" + account + "/activate", null, status().isOk());

        String key = "cash-" + UUID.randomUUID();
        call("/api/v1/accounts/" + account + "/cash-deposits", "{\"amount\":\"250.00\"}", status().isCreated(),
                "Idempotency-Key", key);
        call("/api/v1/accounts/" + account + "/cash-deposits", "{\"amount\":\"250.00\"}", status().isOk(),
                "Idempotency-Key", key);
        mvc.perform(get("/api/v1/accounts/" + account)).andExpect(jsonPath("$.ledgerBalance").value("250.00"));

        JsonNode loan = call("/api/v1/loans", """
                {"customerId":"%s","settlementAccountId":"%s","principal":"1200.00","currency":"USD",
                 "annualRateBps":600,"installmentCount":6}""".formatted(customer, account), status().isCreated());
        String loanId = loan.get("id").asText();
        assertThat(loan.get("status").asText()).isEqualTo("PROPOSED");
        call("/api/v1/loans/" + loanId + "/disburse", null, status().isConflict()); // not approved yet
        call("/api/v1/loans/" + loanId + "/approve", "{\"rationale\":\"affordable\"}", status().isOk());
        JsonNode active = call("/api/v1/loans/" + loanId + "/disburse", null, status().isOk());
        assertThat(active.get("schedule")).hasSize(6);
        assertThat(active.get("ledgerOutstandingPrincipalMinor").asLong()).isEqualTo(120_000);
        assertThat(active.get("decisions").get(0).get("decision").asText()).isEqualTo("APPROVED");

        // Nothing is due on the day of disbursement: only the early-settlement amount is accepted.
        call("/api/v1/loans/" + loanId + "/repayments", "{\"amount\":\"100.00\"}",
                status().isUnprocessableEntity(), "Idempotency-Key", "rp-" + UUID.randomUUID());
        call("/api/v1/loans/" + loanId + "/repayments", "{\"amount\":\"1.00\"}", status().isBadRequest());

        call("/api/v1/customers/" + customer + "/close", null, status().isUnprocessableEntity());
        call("/api/v1/accounts/" + account + "/close", null, status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/ledger/reconciliation")).andExpect(jsonPath("$.consistent").value(true));
    }
}
