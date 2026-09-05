package com.obdeadsoup.devpilot.agent.application.proposal;

public enum AgentToolProposalStatus {
    PENDING_APPROVAL, EXECUTING, EXECUTED, REJECTED, EXPIRED, FAILED;

    public boolean isResolved() {
        return this == EXECUTED || this == REJECTED || this == EXPIRED || this == FAILED;
    }
}
