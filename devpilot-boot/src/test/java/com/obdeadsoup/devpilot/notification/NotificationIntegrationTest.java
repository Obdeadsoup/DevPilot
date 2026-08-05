package com.obdeadsoup.devpilot.notification;

import com.obdeadsoup.devpilot.notification.application.*;
import com.obdeadsoup.devpilot.DevPilotApplication;
import com.obdeadsoup.devpilot.notification.domain.*;
import com.obdeadsoup.devpilot.notification.persistence.mapper.NotificationMapper;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.context.*;import org.testcontainers.containers.MySQLContainer;import org.testcontainers.junit.jupiter.*;
import java.time.LocalDateTime;import java.util.concurrent.*;import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true) @ActiveProfiles("integration-test") @SpringBootTest(classes=DevPilotApplication.class)
class NotificationIntegrationTest {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withDatabaseName("devpilot_notification_test").withUsername("devpilot").withPassword("devpilot_test_password");
 @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);}
 @Autowired JdbcTemplate jdbc;@Autowired NotificationApplicationService app;@Autowired NotificationMapper mapper;@Autowired NotificationReminderScanService scans;
 @BeforeEach void setup(){jdbc.update("DELETE FROM dp_notification");jdbc.update("DELETE FROM dp_task_github_link");jdbc.update("DELETE FROM dp_task_status_history");jdbc.update("DELETE FROM dp_task");jdbc.update("DELETE FROM dp_project_member");jdbc.update("DELETE FROM dp_workspace_member");jdbc.update("DELETE FROM dp_project");jdbc.update("DELETE FROM dp_workspace");jdbc.update("DELETE FROM dp_user");
  jdbc.update("INSERT INTO dp_user(id,username,email,display_name,password_hash,status) VALUES(10,'owner','owner@test.local','Owner','{noop}x','ACTIVE'),(11,'member','member@test.local','Member','{noop}x','ACTIVE')");
  jdbc.update("INSERT INTO dp_workspace(id,name,slug,owner_user_id,status) VALUES(100,'W','notification-w',10,'ACTIVE')");
  jdbc.update("INSERT INTO dp_workspace_member(workspace_id,user_id,role,status,invited_by,joined_at) VALUES(100,11,'MEMBER','ACTIVE',10,NOW(6))");
  jdbc.update("INSERT INTO dp_project(id,workspace_id,name,project_key,status,visibility,created_by) VALUES(200,100,'P','NP','ACTIVE','INTERNAL',10)");}
 private CreateNotificationCommand command(long user,String key){return new CreateNotificationCommand(user,100,200,NotificationType.TASK_DUE_SOON,"Due","Task NP-1",NotificationTargetType.TASK,1,"/api/v1/workspaces/100/projects/200/tasks/1",NotificationSourceType.TASK,1,key,LocalDateTime.now());}
 @Test void uniqueConstraintAndMultipleRecipients(){assertThat(app.createIfAbsent(command(10,"task:1:due-soon:1"))).isEqualTo(NotificationCreateResult.CREATED);assertThat(app.createIfAbsent(command(10,"task:1:due-soon:1"))).isEqualTo(NotificationCreateResult.ALREADY_EXISTS);assertThat(app.createIfAbsent(command(11,"task:1:due-soon:1"))).isEqualTo(NotificationCreateResult.CREATED);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_notification",Long.class)).isEqualTo(2);}
 @Test void concurrentCreatesAtMostOne() throws Exception {var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);Callable<NotificationCreateResult> c=()->{start.await();return app.createIfAbsent(command(10,"task:2:overdue:1:initial"));};var a=pool.submit(c);var b=pool.submit(c);start.countDown();assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(NotificationCreateResult.CREATED,NotificationCreateResult.ALREADY_EXISTS);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_notification",Long.class)).isEqualTo(1);}finally{pool.shutdownNow();}}
 @Test void readIsRecipientAndVersionScoped(){app.createIfAbsent(command(10,"task:3:due-soon:1"));long id=jdbc.queryForObject("SELECT id FROM dp_notification",Long.class);assertThat(mapper.markRead(id,11,0,LocalDateTime.now())).isZero();assertThat(mapper.markRead(id,10,99,LocalDateTime.now())).isZero();assertThat(mapper.markRead(id,10,0,LocalDateTime.now())).isEqualTo(1);assertThat(mapper.unreadCount(10)).isZero();}
 @Test void dueSoonScanIsRepeatable(){jdbc.update("INSERT INTO dp_task(id,workspace_id,project_id,title,status,priority,reporter_user_id,assignee_user_id,due_at) VALUES(1,100,200,'Due task','TODO','MEDIUM',10,11,DATE_ADD(NOW(6),INTERVAL 1 HOUR))");scans.scan();scans.scan();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_notification WHERE notification_type='TASK_DUE_SOON'",Long.class)).isEqualTo(1);}
}
