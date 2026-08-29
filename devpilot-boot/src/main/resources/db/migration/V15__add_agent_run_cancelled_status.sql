ALTER TABLE dp_agent_run DROP CHECK chk_agent_run_terminal_fields;
ALTER TABLE dp_agent_run DROP CHECK chk_agent_run_status;

ALTER TABLE dp_agent_run
    ADD CONSTRAINT chk_agent_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT chk_agent_run_terminal_fields CHECK (
        (status = 'RUNNING' AND final_output IS NULL AND failure_kind IS NULL AND finished_at IS NULL)
        OR (status = 'SUCCEEDED' AND final_output IS NOT NULL AND failure_kind IS NULL AND finished_at IS NOT NULL)
        OR (status = 'FAILED' AND final_output IS NULL AND failure_kind IS NOT NULL AND finished_at IS NOT NULL)
        OR (status = 'CANCELLED' AND final_output IS NULL AND failure_kind IS NULL AND finished_at IS NOT NULL)
    );
