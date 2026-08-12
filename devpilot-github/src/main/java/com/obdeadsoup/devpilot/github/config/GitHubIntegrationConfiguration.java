package com.obdeadsoup.devpilot.github.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.obdeadsoup.devpilot.github.application.GitHubDeliveryRetryPolicy;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdTaskDecorator;

import java.time.Clock;

/**
 * GitHub 集成的基础运行时装配，提供 UTC Clock、Delivery 异步线程池和 Delivery Retry Policy。
 * API Client 的 HTTP 装配由 {@link GitHubRestClientConfiguration} 单独维护。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        GitHubIntegrationProperties.class,
        GitHubReconciliationProperties.class,
        GitHubBacklogProperties.class
})
@EnableAsync
@EnableScheduling
public class GitHubIntegrationConfiguration {

    @Bean
    Clock githubClock() {
        return Clock.systemUTC();
    }

    @Bean("githubDeliveryTaskExecutor")
    TaskExecutor githubDeliveryTaskExecutor(
            GitHubIntegrationProperties properties,
            CorrelationIdTaskDecorator correlationIdTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerCoreThreads());
        executor.setMaxPoolSize(properties.workerMaxThreads());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setThreadNamePrefix("github-delivery-");
        executor.setTaskDecorator(correlationIdTaskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    GitHubDeliveryRetryPolicy githubDeliveryRetryPolicy(GitHubIntegrationProperties properties) {
        return new GitHubDeliveryRetryPolicy(
                properties.deliveryMaxRetries(),
                properties.deliveryRetryInitialDelay(),
                properties.deliveryRetryMaxDelay()
        );
    }
}
