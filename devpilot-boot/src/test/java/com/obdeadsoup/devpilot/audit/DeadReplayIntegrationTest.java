package com.obdeadsoup.devpilot.audit;

import com.obdeadsoup.devpilot.DevPilotApplication;
import com.obdeadsoup.devpilot.audit.application.GitHubSyncReplayApplicationService;
import com.obdeadsoup.devpilot.audit.application.OutboxReplayApplicationService;
import com.obdeadsoup.devpilot.audit.domain.ReplayRequest;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import org.junit.jupiter.api.*;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
@ActiveProfiles("integration-test")
@SpringBootTest(classes= DevPilotApplication.class)
class DeadReplayIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_dead_replay_test").withUsername("devpilot").withPassword("devpilot_test_password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",MYSQL::getJdbcUrl);registry.add("spring.datasource.username",MYSQL::getUsername);
        registry.add("spring.datasource.password",MYSQL::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired OutboxReplayApplicationService outbox;
    @Autowired GitHubSyncReplayApplicationService sync;

    @BeforeEach void setup(){
        jdbc.update("DELETE FROM dp_audit_log");
        jdbc.update("DELETE FROM dp_outbox_event WHERE replay_of_event_id IS NOT NULL");
        jdbc.update("DELETE FROM dp_outbox_event");
        jdbc.update("DELETE FROM dp_github_sync_run WHERE replay_of_run_id IS NOT NULL");
        jdbc.update("DELETE FROM dp_github_sync_run");jdbc.update("DELETE FROM dp_github_sync_checkpoint");
        jdbc.update("DELETE FROM dp_github_repository");jdbc.update("DELETE FROM dp_task");
        jdbc.update("DELETE FROM dp_project_member");jdbc.update("DELETE FROM dp_workspace_member");
        jdbc.update("DELETE FROM dp_project");jdbc.update("DELETE FROM dp_workspace");jdbc.update("DELETE FROM dp_user");
        jdbc.update("INSERT INTO dp_user(id,username,email,display_name,password_hash,status) VALUES(10,'owner','owner@test.local','Owner','{noop}x','ACTIVE')");
        jdbc.update("INSERT INTO dp_workspace(id,name,slug,owner_user_id,status) VALUES(100,'W','dead-replay-w',10,'ACTIVE')");
        jdbc.update("INSERT INTO dp_project(id,workspace_id,name,project_key,status,visibility,created_by) VALUES(200,100,'P','DR','ACTIVE','INTERNAL',10)");
        jdbc.update("INSERT INTO dp_task(id,workspace_id,project_id,title,status,priority,reporter_user_id) VALUES(300,100,200,'T','TODO','MEDIUM',10)");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new DevPilotUserPrincipal(10,"owner","owner@test.local","Owner"),null,List.of()));
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void outboxReplayPreservesOriginalAndCreatesAuditedPendingCopy(){
        jdbc.update("""
                INSERT INTO dp_outbox_event(id,event_key,aggregate_type,aggregate_id,event_type,schema_version,payload_json,
                  processing_status,retry_count,last_error_code,last_error_message,occurred_at,version)
                VALUES(400,'original','TASK',300,'TASK_COMPLETED_V1',1,JSON_OBJECT('taskId',300,'dedupeKey','stable'),
                  'DEAD',4,'HANDLER_FAILURE','safe failure',NOW(6),7)
                """);
        var receipt=outbox.replay(100,200,400,new ReplayRequest("已修复处理器版本兼容问题，现在重新执行。",7L));
        assertThat(receipt.status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForMap("SELECT processing_status,retry_count,version FROM dp_outbox_event WHERE id=400"))
                .containsEntry("processing_status","DEAD").containsEntry("retry_count",4).containsEntry("version",7L);
        var replay=jdbc.queryForMap("SELECT processing_status,retry_count,replay_of_event_id,replay_sequence,replay_requested_by,replay_reason,CAST(payload_json AS CHAR) payload FROM dp_outbox_event WHERE id=?",receipt.replayId());
        assertThat(replay).containsEntry("processing_status","PENDING").containsEntry("retry_count",0)
                .containsEntry("replay_of_event_id",400L).containsEntry("replay_sequence",1)
                .containsEntry("replay_requested_by",10L);
        assertThat(replay.get("payload").toString()).contains("stable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_audit_log WHERE resource_id='400' AND result='SUCCESS'",Long.class)).isEqualTo(2);
    }

    @Test void syncReplayPreservesDeadRunAndDoesNotMoveCheckpoint(){
        jdbc.update("""
                INSERT INTO dp_github_repository(id,workspace_id,project_id,github_repository_id,owner_login,repository_name,
                  full_name,binding_status,webhook_secret_ref,api_credential_ref,created_by)
                VALUES(500,100,200,9001,'o','r','o/r','ACTIVE','WEBHOOK_REF','API_REF',10)
                """);
        LocalDateTime checkpoint=LocalDateTime.of(2026,1,2,3,4,5);
        jdbc.update("INSERT INTO dp_github_sync_checkpoint(repository_binding_id,resource_type,last_successful_sync_at,overlap_seconds) VALUES(500,'COMMIT',?,300)",checkpoint);
        jdbc.update("INSERT INTO dp_github_sync_run(id,repository_binding_id,resource_type,trigger_type,status,attempt_count,requested_by,version) VALUES(600,500,'COMMIT','MANUAL','DEAD',5,10,3)");
        var receipt=sync.replay(100,200,500,600,new ReplayRequest("已排除 GitHub 临时故障，按可靠水位重新同步。",3L));
        assertThat(jdbc.queryForObject("SELECT status FROM dp_github_sync_run WHERE id=600",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForMap("SELECT status,trigger_type,replay_of_run_id,replay_sequence,replay_requested_by FROM dp_github_sync_run WHERE id=?",receipt.replayId()))
                .containsEntry("status","PENDING").containsEntry("trigger_type","MANUAL_REPLAY")
                .containsEntry("replay_of_run_id",600L).containsEntry("replay_sequence",1).containsEntry("replay_requested_by",10L);
        assertThat(jdbc.queryForObject("SELECT last_successful_sync_at FROM dp_github_sync_checkpoint WHERE repository_binding_id=500",LocalDateTime.class)).isEqualTo(checkpoint);
    }

    @Test void nonDeadReplayIsRejectedAndFailureAuditSurvivesRollback(){
        jdbc.update("INSERT INTO dp_outbox_event(id,event_key,aggregate_type,aggregate_id,event_type,schema_version,payload_json,processing_status,retry_count,occurred_at,version) VALUES(401,'processed','TASK',300,'TASK_COMPLETED_V1',1,JSON_OBJECT('taskId',300),'PROCESSED',0,NOW(6),2)");
        assertThatThrownBy(()->outbox.replay(100,200,401,new ReplayRequest("尝试重放一条非 DEAD 事件以验证拒绝审计。",2L)))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_outbox_event WHERE replay_of_event_id=401",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_audit_log WHERE resource_id='401' AND result='FAILURE'",Long.class)).isEqualTo(1);
    }
}
