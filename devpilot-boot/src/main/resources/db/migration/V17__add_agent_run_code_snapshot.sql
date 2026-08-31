ALTER TABLE dp_agent_run
    ADD COLUMN repository_full_name VARCHAR(510) NULL AFTER user_input,
    ADD COLUMN branch_name VARCHAR(255) NULL AFTER repository_full_name,
    ADD COLUMN commit_sha VARCHAR(64) NULL AFTER branch_name,
    ADD CONSTRAINT chk_agent_run_commit_sha CHECK (
        commit_sha IS NULL OR commit_sha REGEXP '^[0-9a-f]{40,64}$'
    ),
    ADD CONSTRAINT chk_agent_run_code_snapshot CHECK (
        (repository_full_name IS NULL AND branch_name IS NULL AND commit_sha IS NULL)
        OR (repository_full_name IS NOT NULL AND branch_name IS NOT NULL AND commit_sha IS NOT NULL)
    );
