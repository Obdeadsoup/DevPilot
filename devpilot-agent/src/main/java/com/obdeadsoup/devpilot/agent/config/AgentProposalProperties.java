package com.obdeadsoup.devpilot.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("devpilot.agent.proposal")
public record AgentProposalProperties(
        @DefaultValue("15m") Duration ttl,
        @DefaultValue("100") int expirationBatchSize) {
    public AgentProposalProperties {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || expirationBatchSize < 1 || expirationBatchSize > 1000) {
            throw new IllegalArgumentException("proposal limits must be positive");
        }
    }
}
