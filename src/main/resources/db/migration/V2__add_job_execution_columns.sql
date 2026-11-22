-- Execution tracking for workers (M2). Leases and attempt counts arrive with M3.

ALTER TABLE jobs
    ADD COLUMN worker_id   uuid,
    ADD COLUMN started_at  timestamptz,
    ADD COLUMN finished_at timestamptz,
    ADD COLUMN last_error  text,

    ADD CONSTRAINT jobs_running_has_worker CHECK (
        status <> 'RUNNING' OR (worker_id IS NOT NULL AND started_at IS NOT NULL)
    ),
    ADD CONSTRAINT jobs_finished_has_finished_at CHECK (
        status NOT IN ('SUCCEEDED', 'FAILED') OR finished_at IS NOT NULL
    ),
    ADD CONSTRAINT jobs_last_error_length CHECK (char_length(last_error) <= 4000);

-- Workers poll for due PENDING jobs in (run_at, id) order. A partial index keeps that
-- cheap however many finished jobs the table holds. Built without CONCURRENTLY because
-- Flyway runs migrations in a transaction; revisit if the table is large when this runs.
CREATE INDEX jobs_pending_run_at_idx ON jobs (run_at, id) WHERE status = 'PENDING';

COMMENT ON COLUMN jobs.worker_id IS 'Worker process that claimed the job most recently';
COMMENT ON COLUMN jobs.started_at IS 'When the most recent attempt was claimed';
COMMENT ON COLUMN jobs.finished_at IS 'When the job reached SUCCEEDED or FAILED';
COMMENT ON COLUMN jobs.last_error IS 'Error of the most recent failed attempt, truncated';
