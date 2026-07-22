package com.obdeadsoup.devpilot.github.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubIntegrationProperties.class)
@EnableAsync
public class GitHubIntegrationConfiguration {

    @Bean
    Clock githubClock() {
        return Clock.systemUTC();
    }

    @Bean("githubDeliveryTaskExecutor")
    TaskExecutor githubDeliveryTaskExecutor(GitHubIntegrationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerCoreThreads());
        executor.setMaxPoolSize(properties.workerMaxThreads());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setThreadNamePrefix("github-delivery-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
