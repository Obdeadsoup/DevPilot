package com.obdeadsoup.devpilot;

import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import com.obdeadsoup.devpilot.identity.persistence.mapper.WorkspaceMapper;
import com.obdeadsoup.devpilot.identity.persistence.mapper.WorkspaceMemberMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@TestConfiguration(proxyBeanMethods = false)
class IsolatedPersistenceTestConfiguration {

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
