-- =====================================================================
-- V4 : Idempotency and audit provenance
--
-- Two cross-cutting concerns that are part of the financial core rather
-- than infrastructure decoration:
--
--   idempotency_record : a client retry must never move money twice.
--   audit_event        : every consequential state change must be
--                        attributable and replayable by a later
--                        intelligence layer.
--
-- audit_event is intentionally shaped like a transactional outbox
-- (monotonic sequence + JSONB payload + correlation/causation). When an
-- event bus is genuinely required it can be drained from here without
-- changing the financial core. No broker is introduced in this phase.
-- =====================================================================

CREATE TABLE idempotency_record (
    id                  UUID        PRIMARY KEY,
    scope               TEXT        NOT NULL,
    idempotency_key     TEXT        NOT NULL,
    request_fingerprint TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    resource_id         UUID,
    response_status     SMALLINT,
    response_body       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,

    CONSTRAINT idempotency_scope_key_unique UNIQUE (scope, idempotency_key),
    CONSTRAINT idempotency_status_valid CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT idempotency_completed_consistency CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL AND response_body IS NOT NULL)
     OR (status = 'IN_PROGRESS')
    ),
    CONSTRAINT idempotency_key_not_blank CHECK (length(btrim(idempotency_key)) > 0)
);

CREATE INDEX idempotency_created_at_idx ON idempotency_record (created_at);

COMMENT ON TABLE idempotency_record IS
    'Request-level deduplication. Committed in the same transaction as the effect it guards.';


CREATE TABLE audit_event (
    id               UUID        PRIMARY KEY,
    sequence_no      BIGINT      GENERATED ALWAYS AS IDENTITY,
    event_type       TEXT        NOT NULL,
    aggregate_type   TEXT        NOT NULL,
    aggregate_id     UUID        NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    recorded_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor            TEXT        NOT NULL,
    actor_type       TEXT        NOT NULL,
    correlation_id   UUID        NOT NULL,
    causation_id     UUID,
    source_operation TEXT        NOT NULL,
    schema_version   SMALLINT    NOT NULL DEFAULT 1,
    payload          JSONB       NOT NULL,

    CONSTRAINT audit_event_sequence_unique UNIQUE (sequence_no),
    CONSTRAINT audit_event_actor_type_valid
        CHECK (actor_type IN ('HUMAN', 'SYSTEM', 'SERVICE', 'AGENT')),
    CONSTRAINT audit_event_payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX audit_event_aggregate_idx   ON audit_event (aggregate_type, aggregate_id, sequence_no);
CREATE INDEX audit_event_correlation_idx ON audit_event (correlation_id, sequence_no);
CREATE INDEX audit_event_occurred_at_idx ON audit_event (occurred_at DESC);
CREATE INDEX audit_event_type_idx        ON audit_event (event_type);

COMMENT ON TABLE audit_event IS
    'Append-only provenance log. Outbox-shaped so an event bus can be added later without rework.';

CREATE TRIGGER audit_event_is_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION wbank_forbid_mutation();
