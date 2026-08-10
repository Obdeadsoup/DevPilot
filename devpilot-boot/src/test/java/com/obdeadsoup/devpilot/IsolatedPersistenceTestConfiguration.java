package com.obdeadsoup.devpilot;

import com.obdeadsoup.devpilot.audit.persistence.mapper.AuditLogMapper;
import com.obdeadsoup.devpilot.audit.persistence.mapper.DeadLetterMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubCommitMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubIssueMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestReviewMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMemberMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskGitHubLinkMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskStatusHistoryMapper;
import com.obdeadsoup.devpilot.notification.persistence.mapper.NotificationMapper;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxEventMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@TestConfiguration(proxyBeanMethods = false)
class IsolatedPersistenceTestConfiguration {
    @Bean AuditLogMapper auditLogMapper() { return Mockito.mock(AuditLogMapper.class); }
    @Bean DeadLetterMapper deadLetterMapper() { return Mockito.mock(DeadLetterMapper.class); }
    @Bean OutboxEventMapper outboxEventMapper() { return Mockito.mock(OutboxEventMapper.class); }
    @Bean NotificationMapper notificationMapper() { return Mockito.mock(NotificationMapper.class); }

    @Bean
    ProjectMapper projectMapper() {
        return Mockito.mock(ProjectMapper.class);
    }

    @Bean
    ProjectActivityMapper projectActivityMapper() {
        return Mockito.mock(ProjectActivityMapper.class);
    }

    @Bean
    ProjectMemberMapper projectMemberMapper() {
        return Mockito.mock(ProjectMemberMapper.class);
    }

    @Bean
    GitHubRepositoryMapper gitHubRepositoryMapper() {
        return Mockito.mock(GitHubRepositoryMapper.class);
    }

    @Bean
    GitHubDeliveryMapper gitHubDeliveryMapper() {
        return Mockito.mock(GitHubDeliveryMapper.class);
    }

    @Bean
    GitHubCommitMapper gitHubCommitMapper() {
        return Mockito.mock(GitHubCommitMapper.class);
    }

    @Bean
    TaskMapper taskMapper() { return Mockito.mock(TaskMapper.class); }

    @Bean
    TaskStatusHistoryMapper taskStatusHistoryMapper() { return Mockito.mock(TaskStatusHistoryMapper.class); }

    @Bean
    TaskGitHubLinkMapper taskGitHubLinkMapper() { return Mockito.mock(TaskGitHubLinkMapper.class); }

    @Bean
    GitHubIssueMapper gitHubIssueMapper() {
        return Mockito.mock(GitHubIssueMapper.class);
    }

    @Bean
    GitHubPullRequestMapper gitHubPullRequestMapper() {
        return Mockito.mock(GitHubPullRequestMapper.class);
    }

    @Bean
    GitHubPullRequestReviewMapper gitHubPullRequestReviewMapper() {
        return Mockito.mock(GitHubPullRequestReviewMapper.class);
    }

    @Bean
    GitHubSyncCheckpointMapper gitHubSyncCheckpointMapper() {
        return Mockito.mock(GitHubSyncCheckpointMapper.class);
    }

    @Bean
    GitHubSyncRunMapper gitHubSyncRunMapper() {
        return Mockito.mock(GitHubSyncRunMapper.class);
    }

    @Bean
    UserMapper userMapper() {
        return Mockito.mock(UserMapper.class);
    }

    @Bean
    WorkspaceMapper workspaceMapper() {
        return Mockito.mock(WorkspaceMapper.class);
    }

    @Bean
    WorkspaceMemberMapper workspaceMemberMapper() {
        return Mockito.mock(WorkspaceMemberMapper.class);
    }

    @Bean
    StringRedisTemplate stringRedisTemplate() {
        return Mockito.mock(StringRedisTemplate.class);
    }
}
