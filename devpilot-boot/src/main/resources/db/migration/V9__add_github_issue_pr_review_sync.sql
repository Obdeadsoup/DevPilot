CREATE TABLE dp_github_issue (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    repository_binding_id BIGINT NOT NULL,
    github_repository_id BIGINT NOT NULL,
    github_issue_id BIGINT NOT NULL,
    issue_number INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    body TEXT NULL,
    state VARCHAR(20) NOT NULL,
    state_reason VARCHAR(100) NULL,
    author_github_user_id BIGINT NULL,
    author_login VARCHAR(100) NULL,
    assignee_summary_json JSON NOT NULL,
    labels_json JSON NOT NULL,
    html_url VARCHAR(500) NOT NULL,
    github_created_at DATETIME(6) NOT NULL,
    github_updated_at DATETIME(6) NOT NULL,
    github_closed_at DATETIME(6) NULL,
    first_seen_source VARCHAR(30) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_issue_repository_id (github_repository_id, github_issue_id),
    UNIQUE KEY uk_github_issue_repository_number (github_repository_id, issue_number),
    KEY idx_github_issue_repository_updated (github_repository_id, github_updated_at DESC, id DESC),
    KEY idx_github_issue_project_state_updated (workspace_id, project_id, state, github_updated_at DESC, id DESC),
    KEY idx_github_issue_binding (repository_binding_id),
    CONSTRAINT fk_github_issue_repository_scope
        FOREIGN KEY (repository_binding_id, workspace_id, project_id)
        REFERENCES dp_github_repository (id, workspace_id, project_id),
    CONSTRAINT chk_github_issue_state CHECK (state IN ('OPEN', 'CLOSED')),
    CONSTRAINT chk_github_issue_source CHECK (
        first_seen_source IN ('WEBHOOK', 'API_BACKFILL', 'API_RECONCILE')
    ),
    CONSTRAINT chk_github_issue_identity CHECK (github_issue_id > 0 AND issue_number > 0),
    CONSTRAINT chk_github_issue_json CHECK (
        CHAR_LENGTH(CAST(assignee_summary_json AS CHAR)) <= 4000
        AND CHAR_LENGTH(CAST(labels_json AS CHAR)) <= 4000
    ),
    CONSTRAINT chk_github_issue_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 10000),
    CONSTRAINT chk_github_issue_hash CHECK (content_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_github_issue_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_github_pull_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    repository_binding_id BIGINT NOT NULL,
    github_repository_id BIGINT NOT NULL,
    github_pull_request_id BIGINT NOT NULL,
    github_issue_id BIGINT NULL,
    pull_request_number INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    body TEXT NULL,
    status VARCHAR(20) NOT NULL,
    draft TINYINT NOT NULL,
    author_github_user_id BIGINT NULL,
    author_login VARCHAR(100) NULL,
    head_ref VARCHAR(255) NOT NULL,
    head_sha CHAR(40) NOT NULL,
    base_ref VARCHAR(255) NOT NULL,
    base_sha CHAR(40) NOT NULL,
    merge_commit_sha CHAR(40) NULL,
    requested_reviewers_json JSON NOT NULL,
    assignee_summary_json JSON NOT NULL,
    labels_json JSON NOT NULL,
    html_url VARCHAR(500) NOT NULL,
    github_created_at DATETIME(6) NOT NULL,
    github_updated_at DATETIME(6) NOT NULL,
    github_closed_at DATETIME(6) NULL,
    github_merged_at DATETIME(6) NULL,
    reviews_synced_at DATETIME(6) NULL,
    first_seen_source VARCHAR(30) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_pr_repository_id (github_repository_id, github_pull_request_id),
    UNIQUE KEY uk_github_pr_repository_number (github_repository_id, pull_request_number),
    UNIQUE KEY uk_github_pr_local_scope (
        id, repository_binding_id, workspace_id, project_id, github_repository_id
    ),
    KEY idx_github_pr_repository_updated (github_repository_id, github_updated_at DESC, id DESC),
    KEY idx_github_pr_project_status_updated (workspace_id, project_id, status, github_updated_at DESC, id DESC),
    KEY idx_github_pr_review_scan (repository_binding_id, reviews_synced_at, github_updated_at, id),
    CONSTRAINT fk_github_pr_repository_scope
        FOREIGN KEY (repository_binding_id, workspace_id, project_id)
        REFERENCES dp_github_repository (id, workspace_id, project_id),
    CONSTRAINT chk_github_pr_status CHECK (status IN ('OPEN', 'CLOSED', 'MERGED')),
    CONSTRAINT chk_github_pr_source CHECK (
        first_seen_source IN ('WEBHOOK', 'API_BACKFILL', 'API_RECONCILE')
    ),
    CONSTRAINT chk_github_pr_identity CHECK (
        github_pull_request_id > 0 AND pull_request_number > 0
    ),
    CONSTRAINT chk_github_pr_draft CHECK (draft IN (0, 1)),
    CONSTRAINT chk_github_pr_sha CHECK (
        head_sha REGEXP '^[0-9a-f]{40}$'
        AND base_sha REGEXP '^[0-9a-f]{40}$'
        AND (merge_commit_sha IS NULL OR merge_commit_sha REGEXP '^[0-9a-f]{40}$')
    ),
    CONSTRAINT chk_github_pr_json CHECK (
        CHAR_LENGTH(CAST(requested_reviewers_json AS CHAR)) <= 4000
        AND CHAR_LENGTH(CAST(assignee_summary_json AS CHAR)) <= 4000
        AND CHAR_LENGTH(CAST(labels_json AS CHAR)) <= 4000
    ),
    CONSTRAINT chk_github_pr_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 10000),
    CONSTRAINT chk_github_pr_hash CHECK (content_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_github_pr_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_github_pull_request_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    repository_binding_id BIGINT NOT NULL,
    github_repository_id BIGINT NOT NULL,
    pull_request_id BIGINT NOT NULL,
    github_review_id BIGINT NOT NULL,
    reviewer_github_user_id BIGINT NULL,
    reviewer_login VARCHAR(100) NULL,
    state VARCHAR(30) NOT NULL,
    body TEXT NULL,
    commit_sha CHAR(40) NOT NULL,
    html_url VARCHAR(500) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    github_updated_at DATETIME(6) NOT NULL,
    first_seen_source VARCHAR(30) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_github_review_repository_id (github_repository_id, github_review_id),
    KEY idx_github_review_pr_submitted (pull_request_id, submitted_at DESC, id DESC),
    KEY idx_github_review_project_submitted (workspace_id, project_id, submitted_at DESC, id DESC),
    CONSTRAINT fk_github_review_pr_scope
        FOREIGN KEY (
            pull_request_id, repository_binding_id, workspace_id, project_id, github_repository_id
        ) REFERENCES dp_github_pull_request (
            id, repository_binding_id, workspace_id, project_id, github_repository_id
        ),
    CONSTRAINT chk_github_review_state CHECK (
        state IN ('COMMENTED', 'APPROVED', 'CHANGES_REQUESTED', 'DISMISSED')
    ),
    CONSTRAINT chk_github_review_source CHECK (
        first_seen_source IN ('WEBHOOK', 'API_BACKFILL', 'API_RECONCILE')
    ),
    CONSTRAINT chk_github_review_identity CHECK (github_review_id > 0),
    CONSTRAINT chk_github_review_sha CHECK (commit_sha REGEXP '^[0-9a-f]{40}$'),
    CONSTRAINT chk_github_review_hash CHECK (content_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_github_review_body CHECK (body IS NULL OR CHAR_LENGTH(body) <= 10000),
    CONSTRAINT chk_github_review_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dp_github_sync_checkpoint
    DROP CHECK chk_github_sync_checkpoint_resource,
    DROP CHECK chk_github_sync_checkpoint_sha,
    ADD CONSTRAINT chk_github_sync_checkpoint_resource CHECK (
        resource_type IN ('COMMIT', 'ISSUE', 'PULL_REQUEST', 'PULL_REQUEST_REVIEW')
    ),
    ADD CONSTRAINT chk_github_sync_checkpoint_sha CHECK (
        (resource_type = 'COMMIT' AND (
            last_seen_commit_sha IS NULL
            OR last_seen_commit_sha REGEXP '^[0-9a-f]{40}$'
        ))
        OR (resource_type <> 'COMMIT' AND last_seen_commit_sha IS NULL)
    );

ALTER TABLE dp_github_sync_run
    DROP CHECK chk_github_sync_run_resource,
    ADD CONSTRAINT chk_github_sync_run_resource CHECK (
        resource_type IN ('COMMIT', 'ISSUE', 'PULL_REQUEST', 'PULL_REQUEST_REVIEW')
    );

ALTER TABLE dp_project_activity
    DROP CHECK chk_project_activity_type,
    ADD CONSTRAINT chk_project_activity_type CHECK (
        activity_type IN (
            'GITHUB_WEBHOOK_PING', 'CODE_PUSHED', 'GITHUB_COMMIT_DISCOVERED',
            'GITHUB_ISSUE_CREATED', 'GITHUB_ISSUE_EDITED', 'GITHUB_ISSUE_CLOSED',
            'GITHUB_ISSUE_REOPENED', 'GITHUB_ISSUE_ASSIGNEES_CHANGED',
            'GITHUB_ISSUE_LABELS_CHANGED', 'GITHUB_PULL_REQUEST_CREATED',
            'GITHUB_PULL_REQUEST_EDITED', 'GITHUB_PULL_REQUEST_READY_FOR_REVIEW',
            'GITHUB_PULL_REQUEST_CONVERTED_TO_DRAFT', 'GITHUB_PULL_REQUEST_SYNCHRONIZED',
            'GITHUB_PULL_REQUEST_CLOSED', 'GITHUB_PULL_REQUEST_REOPENED',
            'GITHUB_PULL_REQUEST_MERGED', 'GITHUB_PULL_REQUEST_REVIEWERS_CHANGED',
            'GITHUB_REVIEW_COMMENTED', 'GITHUB_REVIEW_APPROVED',
            'GITHUB_REVIEW_CHANGES_REQUESTED', 'GITHUB_REVIEW_DISMISSED'
        )
    );
