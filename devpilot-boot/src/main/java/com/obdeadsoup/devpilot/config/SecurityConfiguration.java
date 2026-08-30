package com.obdeadsoup.devpilot.config;

import com.obdeadsoup.devpilot.identity.application.AccessTokenService;
import com.obdeadsoup.devpilot.identity.security.BearerTokenAuthenticationFilter;
import com.obdeadsoup.devpilot.identity.security.BearerTokenResolver;
import com.obdeadsoup.devpilot.identity.security.DatabaseUserDetailsService;
import com.obdeadsoup.devpilot.identity.security.IdentityProperties;
import com.obdeadsoup.devpilot.identity.config.EmailVerificationProperties;
import com.obdeadsoup.devpilot.identity.security.JsonAccessDeniedHandler;
import com.obdeadsoup.devpilot.identity.security.JsonAuthenticationEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.security.SecureRandom;
import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, EmailVerificationProperties.class})
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(
            DatabaseUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecureRandom accessTokenSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter(
            AccessTokenService accessTokenService,
            BearerTokenResolver bearerTokenResolver,
            JsonAuthenticationEntryPoint authenticationEntryPoint
    ) {
        return new BearerTokenAuthenticationFilter(
                accessTokenService,
                bearerTokenResolver,
                authenticationEntryPoint
        );
    }

    @Bean
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            BearerTokenAuthenticationFilter bearerTokenAuthenticationFilter,
            JsonAuthenticationEntryPoint authenticationEntryPoint,
            JsonAccessDeniedHandler accessDeniedHandler,
            Environment environment
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);
        http.exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
        );
        boolean observabilityProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("local") || profile.equals("observability"));
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET,
                            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness",
                            "/livez", "/readyz").permitAll();
            if (observabilityProfile) {
                // 本地/受控运维网络才开放 scrape；生产默认 profile 仍由 exposure + denyAll 双重保护。
                authorize.requestMatchers(HttpMethod.GET, "/actuator/prometheus", "/actuator/metrics", "/actuator/metrics/**")
                        .permitAll();
            }
            authorize
                .requestMatchers(HttpMethod.POST, "/api/v1/github/webhooks").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/verification/email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                .requestMatchers("/api/v1/notifications/**").authenticated()
                .requestMatchers("/api/v1/workspaces/**").authenticated()
                .anyRequest().denyAll();
        });
        http.addFilterBefore(bearerTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
