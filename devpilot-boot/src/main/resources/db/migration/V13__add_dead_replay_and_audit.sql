CREATE TABLE dp_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_type VARCHAR(20) NOT NULL,
    actor_user_id BIGINT NULL,
    workspace_id BIGINT NULL,
    project_id BIGINT NULL,
    action_type VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(100) NOT NULL,
    result VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NULL,
    error_code VARCHAR(100) NULL,
    request_id VARCHAR(100) NULL,
    correlation_id VARCHAR(100) NULL,
    metadata_json JSON NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_scope_time (workspace_id, project_id, occurred_at DESC, id DESC),
    KEY idx_audit_actor_time (actor_user_id, occurred_at DESC, id DESC),
    KEY idx_audit_resource_time (resource_type, resource_id, occurred_at DESC, id DESC),
    KEY idx_audit_action_time (action_type, occurred_at DESC, id DESC),
    KEY idx_audit_result_time (result, occurred_at DESC, id DESC),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES dp_user (id),
    CONSTRAINT fk_audit_workspace FOREIGN KEY (workspace_id) REFERENCES dp_workspace (id),
    CONSTRAINT fk_audit_project_scope FOREIGN KEY (project_id, workspace_id) REFERENCES dp_project (id, workspace_id),
    CONSTRAINT chk_audit_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT chk_audit_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT chk_audit_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM' AND actor_user_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dp_outbox_event
    ADD COLUMN replay_of_event_id BIGINT NULL AFTER version,
    ADD COLUMN replay_sequence INT NOT NULL DEFAULT 0 AFTER replay_of_event_id,
    ADD COLUMN replay_requested_by BIGINT NULL AFTER replay_sequence,
    ADD COLUMN replay_reason VARCHAR(500) NULL AFTER replay_requested_by,
    ADD CONSTRAINT fk_outbox_replay_original FOREIGN KEY (replay_of_event_id) REFERENCES dp_outbox_event (id),
    ADD CONSTRAINT fk_outbox_replay_actor FOREIGN KEY (replay_requested_by) REFERENCES dp_user (id),
    ADD CONSTRAINT chk_outbox_replay_sequence CHECK (replay_sequence >= 0),
    ADD CONSTRAINT chk_outbox_replay_fields CHECK (
        (replay_of_event_id IS NULL AND replay_sequence = 0 AND replay_requested_by IS NULL AND replay_reason IS NULL)
        OR (replay_of_event_id IS NOT NULL AND replay_sequence > 0 AND replay_requested_by IS NOT NULL AND replay_reason IS NOT NULL)
    ),
    ADD UNIQUE KEY uk_outbox_replay_sequence (replay_of_event_id, replay_sequence),
    ADD KEY idx_outbox_replay_status (replay_of_event_id, processing_status, id);

ALTER TABLE dp_github_sync_run
    ADD COLUMN replay_of_run_id BIGINT NULL AFTER version,
    ADD COLUMN replay_sequence INT NOT NULL DEFAULT 0 AFTER replay_of_run_id,
    ADD COLUMN replay_requested_by BIGINT NULL AFTER replay_sequence,
    ADD COLUMN replay_reason VARCHAR(500) NULL AFTER replay_requested_by,
    ADD CONSTRAINT fk_sync_replay_original FOREIGN KEY (replay_of_run_id) REFERENCES dp_github_sync_run (id),
    ADD CONSTRAINT fk_sync_replay_actor FOREIGN KEY (replay_requested_by) REFERENCES dp_user (id),
    ADD CONSTRAINT chk_sync_replay_sequence CHECK (replay_sequence >= 0),
    ADD CONSTRAINT chk_sync_replay_fields CHECK (
        (replay_of_run_id IS NULL AND replay_sequence = 0 AND replay_requested_by IS NULL AND replay_reason IS NULL)
        OR (replay_of_run_id IS NOT NULL AND replay_sequence > 0 AND replay_requested_by IS NOT NULL AND replay_reason IS NOT NULL)
    ),
    ADD UNIQUE KEY uk_sync_replay_sequence (replay_of_run_id, replay_sequence),
    ADD KEY idx_sync_replay_status (replay_of_run_id, status, id);

ALTER TABLE dp_github_sync_run
    DROP CHECK chk_github_sync_run_trigger,
    ADD CONSTRAINT chk_github_sync_run_trigger CHECK (
        trigger_type IN ('SCHEDULED', 'MANUAL', 'INITIAL', 'MANUAL_REPLAY')
    );
