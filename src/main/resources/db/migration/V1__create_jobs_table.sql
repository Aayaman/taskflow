-- Jobs submitted to TaskFlow. PostgreSQL is the source of truth for job state.
-- Columns needed by later milestones (attempts, leases, errors, priority) are added
-- by the migrations of those milestones.

CREATE TABLE jobs (
    id              uuid        NOT NULL DEFAULT uuidv7(),
    type            text        NOT NULL,
    payload         jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    run_at          timestamptz NOT NULL DEFAULT now(),
    idempotency_key text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT jobs_pkey PRIMARY KEY (id),
    CONSTRAINT jobs_type_format CHECK (
        type ~ '^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$' AND char_length(type) <= 100
    ),
    CONSTRAINT jobs_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT jobs_status_valid CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT jobs_idempotency_key_length CHECK (
        char_length(idempotency_key) BETWEEN 1 AND 255
    ),
    CONSTRAINT jobs_idempotency_key_unique UNIQUE (idempotency_key)
);

COMMENT ON TABLE jobs IS 'Background jobs and their current lifecycle state';
COMMENT ON COLUMN jobs.type IS 'Handler name, UPPER_SNAKE_CASE';
COMMENT ON COLUMN jobs.status IS 'PENDING, RUNNING, SUCCEEDED, FAILED or CANCELLED (see JobStatus)';
COMMENT ON COLUMN jobs.run_at IS 'Earliest time the job may run; future values mean retry or scheduled';
COMMENT ON COLUMN jobs.idempotency_key IS 'Optional client key; resubmitting with the same key returns the original job';
