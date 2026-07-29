package com.obdeadsoup.devpilot.github;

import org.springframework.jdbc.core.JdbcTemplate;

final class WebhookTestFixture {

    static final long WORKSPACE_ID = 100L;
    static final long PROJECT_ID = 200L;
    static final long REPOSITORY_ID = 300L;
    static final long OWNER_USER_ID = 10L;
    static final long SECOND_OWNER_USER_ID = 11L;
    static final long GITHUB_REPOSITORY_ID = 123_456L;
    static final String SECRET_REFERENCE = "DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST";

    private final JdbcTemplate jdbcTemplate;

    WebhookTestFixture(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void reset() {
        jdbcTemplate.update("DELETE FROM dp_project_activity");
        jdbcTemplate.update("DELETE FROM dp_github_delivery");
        jdbcTemplate.update("DELETE FROM dp_github_repository");
        jdbcTemplate.update("DELETE FROM dp_project_member");
        jdbcTemplate.update("DELETE FROM dp_workspace_member");
        jdbcTemplate.update("DELETE FROM dp_project");
        jdbcTemplate.update("DELETE FROM dp_workspace");
        jdbcTemplate.update("DELETE FROM dp_user");
    }

    void createActiveBinding() {
        createUser(OWNER_USER_ID, "webhook-owner", "webhook-owner@example.com");
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (?, 'Test Workspace', 'test-workspace', ?, 'ACTIVE')
                """, WORKSPACE_ID, OWNER_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO dp_project (id, workspace_id, name, project_key, status, visibility)
                VALUES (?, ?, 'DevPilot', 'DEV', 'ACTIVE', 'PRIVATE')
                """, PROJECT_ID, WORKSPACE_ID);
        jdbcTemplate.update("""
                INSERT INTO dp_github_repository (
                    id, workspace_id, project_id, github_repository_id, owner_login,
                    repository_name, full_name, binding_status, credential_ref
                ) VALUES (?, ?, ?, ?, 'octo-org', 'devpilot', 'octo-org/devpilot', 'ACTIVE', ?)
                """, REPOSITORY_ID, WORKSPACE_ID, PROJECT_ID, GITHUB_REPOSITORY_ID, SECRET_REFERENCE);
    }

    void disableBinding() {
        jdbcTemplate.update(
                "UPDATE dp_github_repository SET binding_status = 'DISABLED' WHERE id = ?",
                REPOSITORY_ID
        );
    }

    void archiveProject() {
        jdbcTemplate.update(
                "UPDATE dp_project SET status = 'ARCHIVED' WHERE id = ?",
                PROJECT_ID
        );
    }

    void createSecondWorkspaceAndProject() {
        createUser(SECOND_OWNER_USER_ID, "second-owner", "second-owner@example.com");
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (101, 'Other Workspace', 'other-workspace', ?, 'ACTIVE')
                """, SECOND_OWNER_USER_ID);
        jdbcTemplate.update("""
                INSERT INTO dp_project (id, workspace_id, name, project_key, status, visibility)
                VALUES (201, 101, 'Other Project', 'OTHER', 'ACTIVE', 'PRIVATE')
                """);
    }

    private void createUser(long id, String username, String email) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, 'Webhook Test Owner', '{noop}not-used', 'ACTIVE')
                """, id, username, email);
    }
}
