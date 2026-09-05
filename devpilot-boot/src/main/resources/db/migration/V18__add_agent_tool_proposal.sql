ALTER TABLE dp_agent_run DROP CHECK chk_agent_run_terminal_fields;
ALTER TABLE dp_agent_run DROP CHECK chk_agent_run_status;

ALTER TABLE dp_agent_run
    ADD CONSTRAINT chk_agent_run_status
        CHECK (status IN ('RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    ADD CONSTRAINT chk_agent_run_terminal_fields CHECK (
        (status IN ('RUNNING', 'WAITING_APPROVAL') AND final_output IS NULL AND failure_kind IS NULL AND finished_at IS NULL)
        OR (status = 'SUCCEEDED' AND final_output IS NOT NULL AND failure_kind IS NULL AND finished_at IS NOT NULL)
        OR (status = 'FAILED' AND final_output IS NULL AND failure_kind IS NOT NULL AND finished_at IS NOT NULL)
        OR (status = 'CANCELLED' AND final_output IS NULL AND failure_kind IS NULL AND finished_at IS NOT NULL)
    );

CREATE TABLE dp_agent_tool_proposal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    proposal_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    actor_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    canonical_arguments MEDIUMTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    idempotency_key VARCHAR(96) NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    decision_at DATETIME(6) NULL,
    executed_at DATETIME(6) NULL,
    execution_result MEDIUMTEXT NULL,
    resource_id VARCHAR(128) NULL,
    failure_reason VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_proposal_id (proposal_id),
    UNIQUE KEY uk_agent_tool_proposal_call (run_id, tool_call_id),
    UNIQUE KEY uk_agent_tool_proposal_idempotency (idempotency_key),
    KEY idx_agent_tool_proposal_expiry (status, expires_at),
    CONSTRAINT fk_agent_tool_proposal_run FOREIGN KEY (run_id) REFERENCES dp_agent_run (run_id),
    CONSTRAINT fk_agent_tool_proposal_actor FOREIGN KEY (actor_id) REFERENCES dp_user (id),
    CONSTRAINT fk_agent_tool_proposal_project_scope FOREIGN KEY (project_id, workspace_id)
        REFERENCES dp_project (id, workspace_id),
    CONSTRAINT chk_agent_tool_proposal_status CHECK (status IN
        ('PENDING_APPROVAL','EXECUTING','EXECUTED','REJECTED','EXPIRED','FAILED')),
    CONSTRAINT chk_agent_tool_proposal_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
