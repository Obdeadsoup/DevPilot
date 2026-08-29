package com.obdeadsoup.devpilot.agent.application;

/** 取消 RPC 传输失败；不包含远端 description，避免将私有 payload 带回 HTTP。 */
public final class AgentRuntimeCancellationException extends RuntimeException {
    public AgentRuntimeCancellationException() {
        super("Agent Runtime cancel request failed");
    }
}
