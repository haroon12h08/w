package com.wbank.platform.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the provenance of an inbound HTTP request.
 *
 * <p>Correlation id and actor identity are read from headers today because there is
 * no authentication layer yet (deliberately out of scope for phase 1). When
 * authentication arrives, only this filter changes: nothing downstream reads headers.
 */
@Component("wbankRequestContextFilter")
@Order(1)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String ACTOR_HEADER = "X-Actor";
    public static final String ACTOR_TYPE_HEADER = "X-Actor-Type";

    private static final String DEFAULT_ACTOR = "anonymous-operator";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID correlationId = parseCorrelationId(request.getHeader(CORRELATION_ID_HEADER));
        String actor = blankToNull(request.getHeader(ACTOR_HEADER));
        ActorType actorType = parseActorType(request.getHeader(ACTOR_TYPE_HEADER));

        RequestContext.set(new RequestContext.Attributes(
                correlationId, actor == null ? DEFAULT_ACTOR : actor, actorType));

        MDC.put("correlationId", correlationId.toString());
        MDC.put("actor", actor == null ? DEFAULT_ACTOR : actor);
        response.setHeader(CORRELATION_ID_HEADER, correlationId.toString());

        try {
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
            MDC.remove("correlationId");
            MDC.remove("actor");
        }
    }

    private static UUID parseCorrelationId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            // A malformed correlation id must not fail a financial request; it is
            // metadata. We mint a fresh one rather than silently reusing garbage.
            return UUID.randomUUID();
        }
    }

    private static ActorType parseActorType(String raw) {
        if (raw == null || raw.isBlank()) {
            return ActorType.HUMAN;
        }
        try {
            return ActorType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ActorType.HUMAN;
        }
    }

    private static String blankToNull(String raw) {
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }
}
