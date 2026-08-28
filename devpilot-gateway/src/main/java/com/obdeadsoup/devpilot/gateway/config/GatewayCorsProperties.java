package com.obdeadsoup.devpilot.gateway.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 描述浏览器访问 Gateway 时允许的明确 Origin；生产环境必须覆盖本地默认值。 */
@ConfigurationProperties("devpilot.gateway.cors")
public record GatewayCorsProperties(List<String> allowedOrigins, Duration maxAge) {

    public GatewayCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of("http://localhost:5173") : List.copyOf(allowedOrigins);
        maxAge = maxAge == null ? Duration.ofHours(1) : maxAge;
    }
}
