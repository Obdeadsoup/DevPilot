package com.obdeadsoup.devpilot.agent.sse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Heartbeat 不推进 Agent sequence、不落库、不进 replay；发送失败只清理断开的 emitter。 */
@Component
@ConditionalOnProperty(prefix = "devpilot.agent.sse", name = "enabled", havingValue = "true")
public class AgentRunSseHeartbeatScheduler {
    private final AgentRunEventHub hub;

    public AgentRunSseHeartbeatScheduler(AgentRunEventHub hub) {
        this.hub = hub;
    }

    @Scheduled(fixedDelayString = "${devpilot.agent.sse.heartbeat-interval:20s}")
    public void heartbeat() {
        hub.heartbeat();
    }
}
