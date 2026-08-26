package com.obdeadsoup.devpilot.agent.application;

/** async Stub 回调的 Provider-neutral 监听器，保持 Application Core 与 StreamObserver 隔离。 */
public interface AgentRuntimeEventListener {
    void onEvent(AgentStreamEvent event);

    void onError(AgentRuntimeStreamFailureKind failureKind);

    void onCompleted();
}
