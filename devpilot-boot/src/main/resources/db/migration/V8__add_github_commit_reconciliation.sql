CREATE TABLE dp_github_commit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    repository_binding_id BIGINT NOT NULL,
    github_repository_id BIGINT NOT NULL,
    commit_sha CHAR(40) NOT NULL,
    message VARCHAR(2000) NULL,
    author_name VARCHAR(255) NULL,
    author_email VARCHAR(320) NULL,
    author_github_user_id BIGINT NULL,
    author_login VARCHAR(100) NULL,
    committed_at DATETIME(6) NOT NULL,
    html_url VARCHAR(500) NULL,
    first_seen_source VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_commit_repository_sha (github_repository_id, commit_sha),
    KEY idx_github_commit_project_time (workspace_id, project_id, committed_at DESC, id DESC),
    KEY idx_github_commit_repository_time (github_repository_id, committed_at DESC, id DESC),
    KEY idx_github_commit_binding (repository_binding_id),
    CONSTRAINT fk_github_commit_repository_scope
        FOREIGN KEY (repository_binding_id, workspace_id, project_id)
        REFERENCES dp_github_repository (id, workspace_id, project_id),
    CONSTRAINT chk_github_commit_sha CHECK (commit_sha REGEXP '^[0-9a-f]{40}$'),
    CONSTRAINT chk_github_commit_source CHECK (first_seen_source IN ('WEBHOOK', 'API')),
    CONSTRAINT chk_github_commit_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_github_sync_checkpoint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_binding_id BIGINT NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    last_successful_sync_at DATETIME(6) NULL,
    last_seen_commit_sha CHAR(40) NULL,
    overlap_seconds BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_sync_checkpoint_binding_resource (repository_binding_id, resource_type),
    CONSTRAINT fk_github_sync_checkpoint_binding
        FOREIGN KEY (repository_binding_id) REFERENCES dp_github_repository (id),
    CONSTRAINT chk_github_sync_checkpoint_resource CHECK (resource_type = 'COMMIT'),
    CONSTRAINT chk_github_sync_checkpoint_sha CHECK (
        last_seen_commit_sha IS NULL OR last_seen_commit_sha REGEXP '^[0-9a-f]{40}$'
    ),
    CONSTRAINT chk_github_sync_checkpoint_overlap CHECK (overlap_seconds >= 0),
    CONSTRAINT chk_github_sync_checkpoint_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_github_sync_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    repository_binding_id BIGINT NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(500) NULL,
    requested_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    open_run_marker TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'RUNNING', 'RETRY_WAIT') THEN 1 ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_sync_run_open (repository_binding_id, resource_type, open_run_marker),
    KEY idx_github_sync_run_scan (status, next_retry_at, created_at, id),
    KEY idx_github_sync_run_stale (status, started_at, id),
    KEY idx_github_sync_run_binding (repository_binding_id, created_at DESC, id DESC),
    CONSTRAINT fk_github_sync_run_binding
        FOREIGN KEY (repository_binding_id) REFERENCES dp_github_repository (id),
    CONSTRAINT fk_github_sync_run_requested_by
        FOREIGN KEY (requested_by) REFERENCES dp_user (id),
    CONSTRAINT chk_github_sync_run_resource CHECK (resource_type = 'COMMIT'),
    CONSTRAINT chk_github_sync_run_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'INITIAL')),
    CONSTRAINT chk_github_sync_run_status CHECK (
        status IN ('PENDING', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD')
    ),
    CONSTRAINT chk_github_sync_run_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_github_sync_run_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dp_project_activity
    DROP CHECK chk_project_activity_type,
    ADD CONSTRAINT chk_project_activity_type CHECK (
        activity_type IN ('GITHUB_WEBHOOK_PING', 'CODE_PUSHED', 'GITHUB_COMMIT_DISCOVERED')
    );
