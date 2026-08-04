ALTER TABLE dp_project_activity
    DROP CHECK chk_project_activity_source_type,
    DROP CHECK chk_project_activity_type,
    ADD CONSTRAINT chk_project_activity_source_type CHECK (source_type IN ('GITHUB', 'TASK')),
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
            'GITHUB_REVIEW_CHANGES_REQUESTED', 'GITHUB_REVIEW_DISMISSED',
            'TASK_CREATED', 'TASK_ASSIGNED', 'TASK_UNASSIGNED', 'TASK_UPDATED',
            'TASK_PLANNED', 'TASK_RETURNED_TO_BACKLOG', 'TASK_STARTED',
            'TASK_SUBMITTED_FOR_REVIEW', 'TASK_CHANGES_REQUESTED', 'TASK_COMPLETED',
            'TASK_CANCELED', 'TASK_REOPENED', 'TASK_GITHUB_LINKED', 'TASK_GITHUB_UNLINKED'
        )
    );

CREATE TABLE dp_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT NULL,
    status VARCHAR(20) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    assignee_user_id BIGINT NULL,
    due_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    canceled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    deleted TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_local_scope (id, workspace_id, project_id),
    KEY idx_task_project_status (workspace_id, project_id, status, id),
    KEY idx_task_project_assignee_status (workspace_id, project_id, assignee_user_id, status, id),
    KEY idx_task_project_due_at (workspace_id, project_id, due_at, id),
    CONSTRAINT fk_task_project_scope FOREIGN KEY (project_id, workspace_id)
        REFERENCES dp_project (id, workspace_id),
    CONSTRAINT fk_task_reporter FOREIGN KEY (reporter_user_id) REFERENCES dp_user (id),
    CONSTRAINT fk_task_assignee FOREIGN KEY (assignee_user_id) REFERENCES dp_user (id),
    CONSTRAINT chk_task_status CHECK (status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELED')),
    CONSTRAINT chk_task_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT chk_task_description CHECK (description IS NULL OR CHAR_LENGTH(description) <= 10000),
    CONSTRAINT chk_task_version CHECK (version >= 0),
    CONSTRAINT chk_task_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT chk_task_terminal_times CHECK (
        (status = 'DONE' AND completed_at IS NOT NULL AND canceled_at IS NULL)
        OR (status = 'CANCELED' AND canceled_at IS NOT NULL AND completed_at IS NULL)
        OR (status NOT IN ('DONE', 'CANCELED') AND completed_at IS NULL AND canceled_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_task_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    from_status VARCHAR(20) NULL,
    to_status VARCHAR(20) NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    reason VARCHAR(1000) NULL,
    task_version BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_history_version (task_id, task_version),
    KEY idx_task_history_scope (workspace_id, project_id, task_id, occurred_at),
    CONSTRAINT fk_task_history_task_scope FOREIGN KEY (task_id, workspace_id, project_id)
        REFERENCES dp_task (id, workspace_id, project_id),
    CONSTRAINT fk_task_history_actor FOREIGN KEY (actor_user_id) REFERENCES dp_user (id),
    CONSTRAINT chk_task_history_status CHECK (
        (from_status IS NULL OR from_status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELED'))
        AND to_status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'IN_REVIEW', 'DONE', 'CANCELED')
    ),
    CONSTRAINT chk_task_history_action CHECK (
        action IN ('CREATED', 'PLANNED', 'RETURNED_TO_BACKLOG', 'STARTED', 'SUBMITTED_FOR_REVIEW',
                   'CHANGES_REQUESTED', 'COMPLETED', 'CANCELED', 'REOPENED')
    ),
    CONSTRAINT chk_task_history_version CHECK (task_version >= 0),
    CONSTRAINT chk_task_history_created CHECK (
        (action = 'CREATED' AND from_status IS NULL AND to_status = 'BACKLOG' AND task_version = 0)
        OR (action <> 'CREATED' AND from_status IS NOT NULL AND task_version > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dp_github_issue
    ADD UNIQUE KEY uk_github_issue_local_scope (id, repository_binding_id, workspace_id, project_id, github_repository_id);

CREATE TABLE dp_task_github_link (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    repository_binding_id BIGINT NOT NULL,
    github_repository_id BIGINT NOT NULL,
    resource_type VARCHAR(20) NOT NULL,
    relation_type VARCHAR(20) NOT NULL,
    issue_snapshot_id BIGINT NULL,
    pull_request_snapshot_id BIGINT NULL,
    github_object_id BIGINT NOT NULL,
    github_number INT NOT NULL,
    link_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    removed_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    removed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    active_external_identity VARCHAR(100) GENERATED ALWAYS AS (
        CASE WHEN link_status = 'ACTIVE' THEN CONCAT(resource_type, ':', github_repository_id, ':', github_object_id) ELSE NULL END
    ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_link_active_external (active_external_identity),
    KEY idx_task_link_task (workspace_id, project_id, task_id, link_status),
    CONSTRAINT fk_task_link_task_scope FOREIGN KEY (task_id, workspace_id, project_id)
        REFERENCES dp_task (id, workspace_id, project_id),
    CONSTRAINT fk_task_link_repository_scope FOREIGN KEY (repository_binding_id, workspace_id, project_id)
        REFERENCES dp_github_repository (id, workspace_id, project_id),
    CONSTRAINT fk_task_link_issue_scope FOREIGN KEY (
        issue_snapshot_id, repository_binding_id, workspace_id, project_id, github_repository_id
    ) REFERENCES dp_github_issue (id, repository_binding_id, workspace_id, project_id, github_repository_id),
    CONSTRAINT fk_task_link_pr_scope FOREIGN KEY (
        pull_request_snapshot_id, repository_binding_id, workspace_id, project_id, github_repository_id
    ) REFERENCES dp_github_pull_request (id, repository_binding_id, workspace_id, project_id, github_repository_id),
    CONSTRAINT fk_task_link_created_by FOREIGN KEY (created_by) REFERENCES dp_user (id),
    CONSTRAINT fk_task_link_removed_by FOREIGN KEY (removed_by) REFERENCES dp_user (id),
    CONSTRAINT chk_task_link_type CHECK (resource_type IN ('ISSUE', 'PULL_REQUEST')),
    CONSTRAINT chk_task_link_relation CHECK (relation_type IN ('TRACKS', 'IMPLEMENTED_BY', 'RELATED_TO')),
    CONSTRAINT chk_task_link_status CHECK (link_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_task_link_identity CHECK (
        github_object_id > 0 AND github_number > 0 AND (
            (resource_type = 'ISSUE' AND issue_snapshot_id IS NOT NULL AND pull_request_snapshot_id IS NULL)
            OR (resource_type = 'PULL_REQUEST' AND pull_request_snapshot_id IS NOT NULL AND issue_snapshot_id IS NULL)
        )
    ),
    CONSTRAINT chk_task_link_removed CHECK (
        (link_status = 'ACTIVE' AND removed_by IS NULL AND removed_at IS NULL)
        OR (link_status = 'REMOVED' AND removed_by IS NOT NULL AND removed_at IS NOT NULL)
    ),
    CONSTRAINT chk_task_link_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
