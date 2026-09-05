package com.obdeadsoup.devpilot.agent.application.proposal;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentProposalExpirationScheduler {
    private final AgentToolProposalWorkflow workflow;
    public AgentProposalExpirationScheduler(AgentToolProposalWorkflow workflow) { this.workflow = workflow; }

    @Scheduled(fixedDelayString = "${devpilot.agent.proposal.expiration-scan-interval:30s}")
    public void expire() { workflow.expireDue(); }
}
