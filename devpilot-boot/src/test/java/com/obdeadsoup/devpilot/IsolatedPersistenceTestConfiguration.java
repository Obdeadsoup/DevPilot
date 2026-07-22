package com.obdeadsoup.devpilot;

import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

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
    GitHubRepositoryMapper gitHubRepositoryMapper() {
        return Mockito.mock(GitHubRepositoryMapper.class);
    }

    @Bean
    GitHubDeliveryMapper gitHubDeliveryMapper() {
        return Mockito.mock(GitHubDeliveryMapper.class);
    }
}
