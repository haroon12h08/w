package com.wbank.obligation.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wbank.platform.audit.AuditTrail;
import com.wbank.platform.context.OperationContext;
import com.wbank.platform.context.RequestContext;
import com.wbank.platform.error.BusinessRuleViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Versioned credit policy: which rules were in force when. */
@Service
public class CreditPolicyService {

    private final CreditPolicyRepository policies;
    private final ObjectMapper json;
    private final AuditTrail auditTrail;
    private final Clock clock;

    public CreditPolicyService(CreditPolicyRepository policies, ObjectMapper json, AuditTrail auditTrail, Clock clock) {
        this.policies = policies;
        this.json = json;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CreditPolicy inForceAt(Instant at) {
        return policies.findInForceAt(CreditPolicy.LENDING, at).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + CreditPolicy.LENDING + " version in force at " + at));
    }

    @Transactional(readOnly = true)
    public List<CreditPolicy> versions() {
        return versions(CreditPolicy.LENDING);
    }

    @Transactional(readOnly = true)
    public List<CreditPolicy> versions(String policyCode) {
        return policies.findAllVersions(policyCode);
    }

    @Transactional(readOnly = true)
    public CreditPolicy require(String policyCode, int version) {
        return policies.findById(new CreditPolicy.Key(policyCode, version)).orElseThrow(() ->
                new com.wbank.platform.error.NotFoundException("credit_policy.not_found",
                        "Credit policy %s v%d does not exist".formatted(policyCode, version)));
    }

    public CreditPolicyRules rules(CreditPolicy p) {
        try {
            return json.readValue(p.getRules(), CreditPolicyRules.class);
        } catch (Exception e) {
            throw new IllegalStateException("Credit policy " + p.getVersion() + " rules are unreadable", e);
        }
    }

    /**
     * Publishes a new version. It takes effect at {@code effectiveFrom}, which may not be in
     * the past: rules never change retroactively (also enforced by trigger).
     */
    @Transactional
    public CreditPolicy publish(CreditPolicyRules rules, Instant effectiveFrom, String description) {
        return publish(CreditPolicy.LENDING, rules, effectiveFrom, description);
    }

    /**
     * Publishes a version under {@code policyCode}. Only {@value CreditPolicy#LENDING} is ever in
     * force for live decisions; any other code is a research policy, used solely as the
     * alternative in counterfactual evaluation. Versions of every code are append-only.
     */
    @Transactional
    public CreditPolicy publish(String policyCode, CreditPolicyRules rules, Instant effectiveFrom, String description) {
        if (policyCode == null || !policyCode.matches("^[A-Z][A-Z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("Policy code must be UPPER_SNAKE_CASE: " + policyCode);
        }
        OperationContext context = RequestContext.forOperation("credit_policy.publish");
        Instant now = clock.instant();
        Instant effective = effectiveFrom == null ? now : effectiveFrom;
        if (effective.isBefore(now)) {
            throw new BusinessRuleViolationException("credit_policy.retroactive",
                    "A policy version cannot take effect in the past (" + effective + " < " + now + ")");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A policy version needs a description");
        }
        int next = versions(policyCode).stream().mapToInt(CreditPolicy::getVersion).max().orElse(0) + 1;
        try {
            CreditPolicy p = policies.saveAndFlush(CreditPolicy.publish(policyCode, next, effective,
                    json.writeValueAsString(rules), description.strip(), now, context.actor()));
            auditTrail.record("credit_policy.published", "credit_policy",
                    UUID.nameUUIDFromBytes((policyCode + ":" + next).getBytes()), context,
                    Map.of("policyCode", policyCode, "version", next, "effectiveFrom", effective.toString()));
            return p;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
