CREATE TABLE dp_agent_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    user_input TEXT NOT NULL,
    final_output MEDIUMTEXT NULL,
    failure_kind VARCHAR(32) NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_run_run_id (run_id),
    UNIQUE KEY uk_agent_run_request_id (request_id),
    UNIQUE KEY uk_agent_run_scope_id (id, workspace_id, project_id),
    KEY idx_agent_run_scope_time (workspace_id, project_id, created_at DESC, id DESC),
    KEY idx_agent_run_scope_status_time (workspace_id, project_id, status, created_at DESC, id DESC),
    CONSTRAINT fk_agent_run_project_scope FOREIGN KEY (project_id, workspace_id)
        REFERENCES dp_project (id, workspace_id),
    CONSTRAINT fk_agent_run_creator FOREIGN KEY (created_by) REFERENCES dp_user (id),
    CONSTRAINT chk_agent_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_agent_run_version CHECK (version >= 0),
    CONSTRAINT chk_agent_run_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT chk_agent_run_input CHECK (CHAR_LENGTH(user_input) BETWEEN 1 AND 10000),
    CONSTRAINT chk_agent_run_terminal_fields CHECK (
        (status = 'RUNNING' AND final_output IS NULL AND failure_kind IS NULL AND finished_at IS NULL)
        OR (status = 'SUCCEEDED' AND final_output IS NOT NULL AND failure_kind IS NULL AND finished_at IS NOT NULL)
        OR (status = 'FAILED' AND final_output IS NULL AND failure_kind IS NOT NULL AND finished_at IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
