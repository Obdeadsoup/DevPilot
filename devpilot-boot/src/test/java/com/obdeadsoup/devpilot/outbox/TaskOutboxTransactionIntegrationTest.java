package com.obdeadsoup.devpilot.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.obdeadsoup.devpilot.DevPilotApplication;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.TaskApplicationService;
import com.obdeadsoup.devpilot.task.application.TaskWorkflowService;
import com.obdeadsoup.devpilot.task.application.command.CreateTaskCommand;
import com.obdeadsoup.devpilot.task.application.command.UpdateTaskCommand;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@SpringBootTest(classes = DevPilotApplication.class)
class TaskOutboxTransactionIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_task_outbox_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TaskApplicationService tasks;
    @Autowired private TaskWorkflowService workflow;

    @BeforeEach
    void setup() {
        clearData();
        jdbc.update("INSERT INTO dp_user(id,username,email,display_name,password_hash,status) VALUES"
                + "(10,'owner','owner@test.local','Owner','{noop}x','ACTIVE'),"
                + "(11,'member','member@test.local','Member','{noop}x','ACTIVE')");
        jdbc.update("INSERT INTO dp_workspace(id,name,slug,owner_user_id,status) VALUES(100,'W','outbox-w',10,'ACTIVE')");
        jdbc.update("INSERT INTO dp_workspace_member(workspace_id,user_id,role,status,invited_by,joined_at) "
                + "VALUES(100,11,'MEMBER','ACTIVE',10,NOW(6))");
        jdbc.update("INSERT INTO dp_project(id,workspace_id,name,project_key,status,visibility,created_by) "
                + "VALUES(200,100,'Project','OB','ACTIVE','INTERNAL',10)");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new DevPilotUserPrincipal(10, "owner", "owner@test.local", "Owner"), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assignmentAndWorkflowFactsCommitWithHistoryActivityAndOutbox() {
        TaskResponse task = createTask();
        TaskResponse assigned = tasks.assignTask(100, 200, task.id(), 11, 0);
        assertThat(assigned.version()).isEqualTo(1);
        assertThat(eventTypes()).containsExactly("TASK_ASSIGNED_V1");

        workflow.planTask(100, 200, task.id(), 1, null);
        workflow.startTask(100, 200, task.id(), 2, null);
        workflow.submitForReview(100, 200, task.id(), 3, null);
        workflow.requestChanges(100, 200, task.id(), 4, "adjust");
        workflow.submitForReview(100, 200, task.id(), 5, null);
        workflow.completeTask(100, 200, task.id(), 6, null);
        workflow.reopenTask(100, 200, task.id(), 7, null);
        tasks.unassignTask(100, 200, task.id(), 8);

        assertThat(eventTypes()).containsExactly(
                "TASK_ASSIGNED_V1", "TASK_SUBMITTED_FOR_REVIEW_V1", "TASK_CHANGES_REQUESTED_V1",
                "TASK_SUBMITTED_FOR_REVIEW_V1", "TASK_COMPLETED_V1", "TASK_REOPENED_V1", "TASK_UNASSIGNED_V1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_task_status_history WHERE task_id=?", Long.class, task.id()))
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_project_activity WHERE source_task_id=?", Long.class, task.id()))
                .isGreaterThanOrEqualTo(9);
    }

    @Test
    void outboxConflictRollsBackAssignmentAndProfileUpdateCreatesNoEvent() {
        TaskResponse task = createTask();
        jdbc.update("""
                INSERT INTO dp_outbox_event(event_key,aggregate_type,aggregate_id,event_type,schema_version,
                    payload_json,processing_status,retry_count,occurred_at,version)
                VALUES(?, 'TASK', ?, 'TASK_ASSIGNED_V1', 1, JSON_OBJECT('different',true), 'PENDING', 0, NOW(6), 0)
                """, "task:" + task.id() + ":v1:assigned", task.id());

        assertThatThrownBy(() -> tasks.assignTask(100, 200, task.id(), 11, 0))
                .isInstanceOf(OutboxProcessingException.class);
        assertThat(jdbc.queryForObject("SELECT assignee_user_id FROM dp_task WHERE id=?", Long.class, task.id()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM dp_task WHERE id=?", Long.class, task.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_project_activity WHERE source_task_id=?", Long.class, task.id()))
                .isEqualTo(1);

        tasks.updateTaskProfile(100, 200, task.id(),
                new UpdateTaskCommand("Renamed", "safe", TaskPriority.HIGH, null, 0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_outbox_event", Long.class)).isEqualTo(1);
    }

    private TaskResponse createTask() {
        return tasks.createTask(100, 200, new CreateTaskCommand("Reliable task", "private", TaskPriority.MEDIUM, null, null));
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM dp_outbox_event ORDER BY id", String.class);
    }

    private void clearData() {
        jdbc.update("DELETE FROM dp_outbox_event");
        jdbc.update("DELETE FROM dp_notification");
        jdbc.update("DELETE FROM dp_task_github_link");
        jdbc.update("DELETE FROM dp_task_status_history");
        jdbc.update("DELETE FROM dp_project_activity");
        jdbc.update("DELETE FROM dp_task");
        jdbc.update("DELETE FROM dp_project_member");
        jdbc.update("DELETE FROM dp_workspace_member");
        jdbc.update("DELETE FROM dp_project");
        jdbc.update("DELETE FROM dp_workspace");
        jdbc.update("DELETE FROM dp_user");
    }
}
