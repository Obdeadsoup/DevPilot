package com.obdeadsoup.devpilot.knowledge.config;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdTaskDecorator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfiguration {
    @Bean("knowledgeTaskExecutor")
    TaskExecutor knowledgeTaskExecutor(
            KnowledgeProperties properties,
            CorrelationIdTaskDecorator correlationIdTaskDecorator
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerThreads());
        executor.setMaxPoolSize(properties.workerThreads());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setThreadNamePrefix("knowledge-ingestion-");
        executor.setTaskDecorator(correlationIdTaskDecorator);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
