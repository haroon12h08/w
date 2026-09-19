package com.wbank.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.account.domain.Account;
import com.wbank.support.DomainFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

@AutoConfigureMockMvc
class PaymentApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainFixtures d;

    private JsonNode post(String path, String body, String key, ResultMatcher expect) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key).header("X-Actor", "teller-7").header("X-Actor-Type", "HUMAN");
        }
        return json.readTree(mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    @Test
    void submitQueryReplayAndReverse() throws Exception {
        Account a = d.activeAccount("USD");
        Account b = d.activeAccount("USD");
        d.fund(a.getId(), 50_000, "USD");
        String body = """
                {"debtorAccountId":"%s","creditorAccountId":"%s","amount":"125.50","currency":"USD",
                 "remittanceInformation":"rent"}""".formatted(a.getId(), b.getId());
        String key = "api-" + UUID.randomUUID();

        JsonNode created = post("/api/v1/payments", body, key, status().isCreated());
        assertThat(created.get("status").asText()).isEqualTo("SETTLED");
        assertThat(created.get("instruction").get("initiatedBy").asText()).isEqualTo("teller-7");
        assertThat(created.get("settlementPostings")).hasSize(2);
        assertThat(created.get("events")).hasSize(4);
        String id = created.get("id").asText();

        JsonNode replay = post("/api/v1/payments", body, key, status().isOk());
        assertThat(replay.get("id").asText()).isEqualTo(id);
        assertThat(replay.get("replayed").asBoolean()).isTrue();
        post("/api/v1/payments", body.replace("125.50", "125.51"), key, status().isConflict());
        post("/api/v1/payments", body, null, status().isBadRequest());
        post("/api/v1/payments", body.replace("125.50", "1.001"), "k-" + UUID.randomUUID(), status().isBadRequest());

        JsonNode balances = json.readTree(mvc.perform(get("/api/v1/accounts/" + a.getId() + "/balances"))
                .andReturn().getResponse().getContentAsString());
        assertThat(balances.get("ledgerBalanceMinor").asLong()).isEqualTo(50_000 - 12_550);

        JsonNode held = post("/api/v1/payments", body.replace("}", ",\"executeImmediately\":false}")
                .replace("125.50", "100.00"), "h-" + UUID.randomUUID(), status().isCreated());
        assertThat(held.get("status").asText()).isEqualTo("AUTHORIZED");
        balances = json.readTree(mvc.perform(get("/api/v1/accounts/" + a.getId() + "/balances"))
                .andReturn().getResponse().getContentAsString());
        assertThat(balances.get("reservedMinor").asLong()).isEqualTo(10_000);
        assertThat(balances.get("availableMinor").asLong()).isEqualTo(50_000 - 12_550 - 10_000);
        post("/api/v1/payments/" + held.get("id").asText() + "/execute", "{}", null, status().isOk());

        JsonNode reversed = post("/api/v1/payments/" + id + "/reversal", "{\"reason\":\"wrong payee\"}", null,
                status().isOk());
        assertThat(reversed.get("status").asText()).isEqualTo("REVERSED");
        assertThat(reversed.get("reversalPostings")).hasSize(2);
        post("/api/v1/payments/" + id + "/reversal", "{\"reason\":\"again\"}", null, status().isConflict());
        mvc.perform(get("/api/v1/payments/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
