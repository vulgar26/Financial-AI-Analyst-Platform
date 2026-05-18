ALTER TABLE agent_task
ADD COLUMN IF NOT EXISTS request_id varchar(128);

CREATE INDEX IF NOT EXISTS idx_agent_task_request_id
ON agent_task(request_id)
WHERE request_id IS NOT NULL;
