CREATE TABLE IF NOT EXISTS agent_task (
    task_id uuid PRIMARY KEY,
    user_id varchar(128) NOT NULL,
    task_type varchar(64) NOT NULL,
    idempotency_key varchar(128),
    status varchar(32) NOT NULL,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    result_json jsonb,
    retry_count integer NOT NULL DEFAULT 0,
    max_retries integer NOT NULL DEFAULT 0,
    error_message text,
    next_run_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(128),
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_task_user_idempotency
ON agent_task(user_id, idempotency_key)
WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_task_worker_scan
ON agent_task(status, next_run_at, lease_until);

CREATE INDEX IF NOT EXISTS idx_agent_task_user_created
ON agent_task(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_task_event (
    event_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES agent_task(task_id) ON DELETE CASCADE,
    user_id varchar(128) NOT NULL,
    event_type varchar(64) NOT NULL,
    event_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_agent_task_event_task_created
ON agent_task_event(task_id, created_at ASC);

