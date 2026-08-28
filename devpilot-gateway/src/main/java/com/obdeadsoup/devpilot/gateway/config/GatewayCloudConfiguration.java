package com.obdeadsoup.devpilot.gateway.config;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装 Gateway 边缘能力，同时显式限制共享代码只复用无业务语义的请求标识策略。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewayCloudConfiguration {

    @Bean
    CorrelationIdPolicy correlationIdPolicy() {
        return new CorrelationIdPolicy();
    }
}
