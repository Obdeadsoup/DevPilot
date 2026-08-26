package com.obdeadsoup.devpilot.agent.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 装配 AgentRun SSE 配置；Hub 与 Scheduler 继续由组件扫描管理。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRunSseProperties.class)
public class AgentRunStreamConfiguration {
}
