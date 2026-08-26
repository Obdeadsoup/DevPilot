package com.obdeadsoup.devpilot.agent.application;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** 为 AgentRun 提供统一 UTC 业务时间，测试可替换而不依赖其他模块的 Clock Bean。 */
@Component
public class AgentRunTimeProvider {
    private final Clock clock = Clock.systemUTC();

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
