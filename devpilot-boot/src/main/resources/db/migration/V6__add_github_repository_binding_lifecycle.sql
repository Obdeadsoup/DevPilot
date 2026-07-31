ALTER TABLE dp_github_repository
    RENAME COLUMN credential_ref TO webhook_secret_ref;

ALTER TABLE dp_github_repository
    ADD COLUMN api_credential_ref VARCHAR(200) NULL AFTER webhook_secret_ref,
    ADD COLUMN last_verified_at DATETIME(6) NULL AFTER last_synced_at,
    ADD COLUMN created_by BIGINT NULL AFTER last_verified_at;

UPDATE dp_github_repository repository
JOIN dp_project project ON project.id = repository.project_id
JOIN dp_workspace workspace ON workspace.id = repository.workspace_id
SET repository.created_by = COALESCE(project.created_by, workspace.owner_user_id)
WHERE repository.created_by IS NULL;

ALTER TABLE dp_github_repository
    ADD CONSTRAINT fk_github_repository_created_by
        FOREIGN KEY (created_by) REFERENCES dp_user (id),
    ADD CONSTRAINT chk_github_repository_version_nonnegative CHECK (version >= 0),
    DROP INDEX uk_github_repository_external_id,
    DROP INDEX uk_github_repository_workspace_full_name_deleted,
    ADD COLUMN active_github_repository_id BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN deleted = 0 THEN github_repository_id ELSE NULL END
        ) STORED,
    ADD COLUMN active_repository_full_name VARCHAR(201)
        GENERATED ALWAYS AS (
            CASE WHEN deleted = 0 THEN full_name ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uk_github_repository_active_external_id
        UNIQUE (active_github_repository_id),
    ADD CONSTRAINT uk_github_repository_workspace_active_full_name
        UNIQUE (workspace_id, active_repository_full_name);
