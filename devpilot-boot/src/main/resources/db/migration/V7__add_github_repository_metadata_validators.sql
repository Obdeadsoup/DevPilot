ALTER TABLE dp_github_repository
    ADD COLUMN metadata_etag VARCHAR(255) NULL AFTER last_verified_at,
    ADD COLUMN metadata_last_modified DATETIME(6) NULL AFTER metadata_etag;
