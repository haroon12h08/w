package com.wbank.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.support.DomainFixtures;
import com.wbank.support.PostgresIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/** Point-in-time history over HTTP. */
@AutoConfigureMockMvc
class HistoryApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainFixtures d;

    private JsonNode post(String path, String body, ResultMatcher expect) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON).content(body).header("X-Actor", "officer-9"))
                .andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    private JsonNode get(String path) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void reconstructAndTraceOverHttp() throws Exception {
        var customer = d.activeCustomer();
        var account = d.activeAccount(customer.getId(), "USD");
        String loan = post("/api/v1/loans", """
                {"customerId":"%s","settlementAccountId":"%s","principal":"1500.00","currency":"USD",
                 "annualRateBps":900,"installmentCount":6}""".formatted(customer.getId(), account.getId()),
                status().isCreated()).get("id").asText();
        Instant afterApplication = Instant.now();
        Thread.sleep(5);
        JsonNode approved = post("/api/v1/loans/" + loan + "/approve",
                "{\"rationale\":\"stable income\",\"evidence\":{\"declaredMonthlyIncome\":\"4100.00\"}}", status().isOk());
        String decisionId = get("/api/v1/history/loans/" + loan).get("decisions").get(0).get("decisionId").asText();
        post("/api/v1/loans/" + loan + "/disburse", "{}", status().isOk());

        JsonNode then = get("/api/v1/history/loans/" + loan + "?asOf=" + afterApplication);
        assertThat(then.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(then.get("decisions")).isEmpty();
        assertThat(then.get("eventsNotYetVisible").asInt()).isPositive();
        JsonNode nowState = get("/api/v1/history/loans/" + loan);
        assertThat(nowState.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(nowState.get("outstandingPrincipalMinor").asLong()).isEqualTo(150_000);

        JsonNode events = get("/api/v1/history/loans/" + loan + "/events");
        assertThat(events).extracting(e -> e.get("type").asText()).containsExactly("APPLICATION_RECEIVED",
                "CREDIT_DECISION", "LOAN_DISBURSED", "SCHEDULE_ESTABLISHED");

        JsonNode ctx = get("/api/v1/history/decisions/" + decisionId + "?outcomeKnownAt=" + Instant.now());
        assertThat(ctx.get("snapshotVerified").asBoolean()).isTrue();
        assertThat(ctx.get("policyVersion").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(ctx.at("/snapshot/evidence/suppliedByDecider/declaredMonthlyIncome").asText()).isEqualTo("4100.00");
        assertThat(ctx.at("/snapshot/decision/decidedBy").asText()).isEqualTo("officer-9");
        assertThat(ctx.at("/loanBeforeDecision/status").asText()).isEqualTo("PROPOSED");
        // Real clock: the decision event is recorded microseconds after the decision time.
        assertThat(ctx.get("resultingStatus").asText()).isEqualTo("APPROVED");
        assertThat(ctx.at("/outcome/statusAsKnown").asText()).isEqualTo("ACTIVE");

        String decisionEvent = events.get(1).get("id").asText();
        JsonNode correction = post("/api/v1/history/events/" + decisionEvent + "/corrections",
                "{\"field\":\"evidence.declaredMonthlyIncome\",\"correctedValue\":\"3900.00\",\"reason\":\"payslip\"}",
                status().isCreated());
        assertThat(correction.get("type").asText()).isEqualTo("EVENT_CORRECTED");
        post("/api/v1/history/events/" + events.get(2).get("id").asText() + "/corrections",
                "{\"field\":\"rationale\",\"correctedValue\":\"x\",\"reason\":\"y\"}", status().isUnprocessableEntity());

        JsonNode dataset = get("/api/v1/history/datasets/credit-decisions?horizon=P30D");
        assertThat(dataset.get("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(dataset.get("rows")).anySatisfy(r -> assertThat(r.get("decisionId").asText()).isEqualTo(decisionId));
        assertThat(get("/api/v1/credit-policies").get(0).get("version").asInt()).isEqualTo(1);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
    }
}
