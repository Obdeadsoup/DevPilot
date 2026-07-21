package com.obdeadsoup.devpilot.github.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubIntegrationProperties.class)
public class GitHubIntegrationConfiguration {
}
