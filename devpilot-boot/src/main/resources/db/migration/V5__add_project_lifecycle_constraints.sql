ALTER TABLE dp_project
    ADD COLUMN created_by BIGINT NULL AFTER visibility;

UPDATE dp_project p
JOIN dp_workspace w ON w.id = p.workspace_id
SET p.created_by = w.owner_user_id
WHERE p.created_by IS NULL
  AND w.owner_user_id IS NOT NULL;

ALTER TABLE dp_project
    ADD CONSTRAINT fk_project_created_by
        FOREIGN KEY (created_by) REFERENCES dp_user (id),
    ADD CONSTRAINT chk_project_version_nonnegative CHECK (version >= 0),
    DROP INDEX uk_project_workspace_key_deleted,
    ADD COLUMN active_project_key VARCHAR(32)
        GENERATED ALWAYS AS (
            CASE WHEN deleted = 0 THEN project_key ELSE NULL END
        ) STORED,
    ADD CONSTRAINT uk_project_workspace_active_key
        UNIQUE (workspace_id, active_project_key);
