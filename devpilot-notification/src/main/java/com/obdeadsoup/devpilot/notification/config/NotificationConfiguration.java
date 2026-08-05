package com.obdeadsoup.devpilot.notification.config;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(NotificationReminderProperties.class)
public class NotificationConfiguration { }
