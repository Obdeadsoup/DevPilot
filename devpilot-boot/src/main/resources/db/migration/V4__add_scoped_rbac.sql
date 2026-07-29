ALTER TABLE dp_workspace
    ADD COLUMN owner_user_id BIGINT NULL AFTER description,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_at,
    ADD KEY idx_workspace_owner_status (owner_user_id, status),
    ADD CONSTRAINT fk_workspace_owner FOREIGN KEY (owner_user_id) REFERENCES dp_user (id),
    ADD CONSTRAINT chk_workspace_version CHECK (version >= 0);

CREATE TABLE dp_workspace_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INVITED',
    invited_by BIGINT NOT NULL,
    joined_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_member_workspace_user (workspace_id, user_id),
    KEY idx_workspace_member_workspace_status (workspace_id, status),
    KEY idx_workspace_member_user_status (user_id, status),
    CONSTRAINT fk_workspace_member_workspace FOREIGN KEY (workspace_id) REFERENCES dp_workspace (id),
    CONSTRAINT fk_workspace_member_user FOREIGN KEY (user_id) REFERENCES dp_user (id),
    CONSTRAINT fk_workspace_member_inviter FOREIGN KEY (invited_by) REFERENCES dp_user (id),
    CONSTRAINT chk_workspace_member_role CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    CONSTRAINT chk_workspace_member_status CHECK (
        status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REMOVED')
    ),
    CONSTRAINT chk_workspace_member_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE dp_project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_member_project_user (project_id, user_id),
    KEY idx_project_member_project_status (workspace_id, project_id, status),
    KEY idx_project_member_user_status (user_id, status),
    CONSTRAINT fk_project_member_project_scope FOREIGN KEY (project_id, workspace_id)
        REFERENCES dp_project (id, workspace_id),
    CONSTRAINT fk_project_member_user FOREIGN KEY (user_id) REFERENCES dp_user (id),
    CONSTRAINT fk_project_member_creator FOREIGN KEY (created_by) REFERENCES dp_user (id),
    CONSTRAINT chk_project_member_role CHECK (role IN ('PROJECT_ADMIN', 'DEVELOPER', 'VIEWER')),
    CONSTRAINT chk_project_member_status CHECK (status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT chk_project_member_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
