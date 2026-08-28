package com.obdeadsoup.devpilot.gateway.config;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/** 在进入路由前统一处理浏览器 CORS，避免由不同 Core Controller 各自维护边缘策略。 */
@Configuration(proxyBeanMethods = false)
public class GatewayCorsConfiguration {

    @Bean
    CorsWebFilter gatewayCorsWebFilter(GatewayCorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));
        cors.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                GatewayRequestIdFilter.REQUEST_ID_HEADER,
                CorrelationIdPolicy.HEADER_NAME,
                "Last-Event-ID"));
        cors.setExposedHeaders(List.of(
                GatewayRequestIdFilter.REQUEST_ID_HEADER,
                CorrelationIdPolicy.HEADER_NAME));
        cors.setAllowCredentials(false);
        cors.setMaxAge(properties.maxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
