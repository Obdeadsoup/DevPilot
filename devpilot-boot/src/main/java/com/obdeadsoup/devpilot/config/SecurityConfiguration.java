package com.obdeadsoup.devpilot.config;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
        RequestMatcher githubWebhook = request -> HttpMethod.POST.matches(request.getMethod())
                && "/api/v1/github/webhooks".equals(request.getRequestURI());
        http.csrf(csrf -> csrf.ignoringRequestMatchers(githubWebhook));
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                .requestMatchers(githubWebhook).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/workspaces/*/projects/*/activities").authenticated()
                .anyRequest().denyAll()
        );
        return http.build();
    }
}
