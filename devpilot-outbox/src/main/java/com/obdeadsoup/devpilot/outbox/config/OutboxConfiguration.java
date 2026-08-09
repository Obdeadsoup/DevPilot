package com.obdeadsoup.devpilot.outbox.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 装配 Outbox 的有界专用 Executor；拒绝只丢失快速唤醒，数据库候选仍由下一轮扫描恢复。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {

    @Bean("outboxTaskExecutor")
    TaskExecutor outboxTaskExecutor(OutboxProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workerCoreThreads());
        executor.setMaxPoolSize(properties.workerMaxThreads());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setThreadNamePrefix("devpilot-outbox-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
