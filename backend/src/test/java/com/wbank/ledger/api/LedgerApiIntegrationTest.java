package com.wbank.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.support.LedgerFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The ledger HTTP surface, end to end against PostgreSQL. No test transaction: everything commits. */
@AutoConfigureMockMvc
class LedgerApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired LedgerFixtures f;

    String cash;
    String equity;

    @BeforeEach
    void accounts() throws Exception {
        cash = openAccount("ASSET", "INTERNAL_CASH");
        equity = openAccount("EQUITY", "INTERNAL_EQUITY");
    }

    private String openAccount(String type, String purpose) throws Exception {
        String body = """
                {"code":"API-%s","name":"api test","accountType":"%s","currency":"USD","purpose":"%s"}"""
                .formatted(UUID.randomUUID(), type, purpose);
        String res = mvc.perform(post("/api/v1/ledger/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private String journal(String debitAmt, String creditAmt) {
        return """
                {"description":"capital injection","currency":"USD","postings":[
                  {"ledgerAccountId":"%s","direction":"DEBIT","amount":"%s"},
                  {"ledgerAccountId":"%s","direction":"CREDIT","amount":"%s"}]}"""
                .formatted(cash, debitAmt, equity, creditAmt);
    }

    private ResultActions postJournal(String key, String body) throws Exception {
        var req = post("/api/v1/ledger/entries").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    @Test
    void postReplayAndConflict() throws Exception {
        String key = "api-" + UUID.randomUUID();
        String created = postJournal(key, journal("1250.75", "1250.75"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.totalAmountMinor").value(125_075))
                .andExpect(jsonPath("$.entryType").value("ADJUSTMENT"))
                .andReturn().getResponse().getContentAsString();
        String entryId = json.readTree(created).get("journalEntryId").asText();

        postJournal(key, journal("1250.75", "1250.75"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.journalEntryId").value(entryId));

        postJournal(key, journal("1250.76", "1250.76"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ledger.idempotency_key_reused"));

        JsonNode bal = json.readTree(mvc.perform(get("/api/v1/ledger/accounts/" + equity + "/balance"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(bal.get("balanceMinor").asLong()).isEqualTo(125_075);
        assertThat(bal.get("ledgerDerivedBalanceMinor").asLong()).isEqualTo(125_075);
        assertThat(bal.get("balance").asText()).isEqualTo("1250.75");
    }

    @Test
    void invalidRequestsAreRejectedWithoutEffect() throws Exception {
        postJournal(null, journal("1.00", "1.00")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("request.missing_header"));
        postJournal("k-" + UUID.randomUUID(), journal("1.00", "0.99")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ledger.unbalanced_entry"));
        postJournal("k-" + UUID.randomUUID(), journal("1.005", "1.005")).andExpect(status().isBadRequest());
        postJournal("k-" + UUID.randomUUID(), journal("-1.00", "-1.00")).andExpect(status().isBadRequest());
        postJournal("k-" + UUID.randomUUID(), journal("1e2", "1e2")).andExpect(status().isBadRequest());
        postJournal("k-" + UUID.randomUUID(), journal("0.00", "0.00")).andExpect(status().isBadRequest());
        postJournal("k-" + UUID.randomUUID(), journal("1.00", "1.00").replace("\"USD\"", "\"XYZ\""))
                .andExpect(status().isNotFound());

        assertThat(f.postingsOn(UUID.fromString(cash))).isZero();
        assertThat(f.projected(UUID.fromString(equity))).isZero();
    }

    @Test
    void customerDepositAccountsCannotBeOpenedThroughTheLedgerApi() throws Exception {
        mvc.perform(post("/api/v1/ledger/accounts").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"X-%s","name":"x","accountType":"LIABILITY","currency":"USD","purpose":"CUSTOMER_DEPOSIT"}"""
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void reversalThroughTheApi() throws Exception {
        String created = postJournal("rev-" + UUID.randomUUID(), journal("10.00", "10.00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String entryId = json.readTree(created).get("journalEntryId").asText();

        mvc.perform(post("/api/v1/ledger/entries/" + entryId + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"duplicate capture\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"));
        mvc.perform(get("/api/v1/ledger/entries/" + entryId))
                .andExpect(jsonPath("$.status").value("REVERSED"));
        mvc.perform(post("/api/v1/ledger/entries/" + entryId + "/reversal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/ledger/reconciliation"))
                .andExpect(jsonPath("$.consistent").value(true));
        assertThat(f.projected(UUID.fromString(equity))).isZero();
    }
}
