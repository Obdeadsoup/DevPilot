package com.obdeadsoup.devpilot.agent.application;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** 在调用 Python 前生成 requestId/runId，使本地投影与跨进程调用共享同一关联标识。 */
@Component
public class AgentRunIdentityFactory {

    public AgentRunIdentity create() {
        return new AgentRunIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }
}
