ALTER TABLE dp_workspace_member DROP CHECK chk_workspace_member_status;

ALTER TABLE dp_workspace_member
    ADD CONSTRAINT chk_workspace_member_status CHECK (
        status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REJECTED', 'REMOVED')
    );
