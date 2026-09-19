package com.wbank.platform.context;

/**
 * What kind of entity caused a financial state change.
 *
 * <p>{@code AGENT} exists in the vocabulary from day one, and is currently
 * unreachable, precisely because the later autonomous layers must be
 * distinguishable in the audit trail from humans and from ordinary services.
 */
public enum ActorType {
    HUMAN,
    SYSTEM,
    SERVICE,
    AGENT
}
