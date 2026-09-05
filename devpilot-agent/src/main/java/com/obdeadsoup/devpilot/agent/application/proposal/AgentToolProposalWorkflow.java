package com.obdeadsoup.devpilot.agent.application.proposal;

import com.obdeadsoup.devpilot.agent.application.AgentRunPersistenceService;
import com.obdeadsoup.devpilot.agent.application.AgentRunStreamCoordinator;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/** HTTP/scheduler 编排在 Proposal 事务提交后才调用 Python，避免持有数据库事务跨网络等待。 */
@Service
public class AgentToolProposalWorkflow {
    private final AgentToolProposalService proposals;
    private final CurrentUserProvider currentUser;
    private final AgentRunPersistenceService runs;
    private final AgentRunStreamCoordinator coordinator;

    public AgentToolProposalWorkflow(AgentToolProposalService proposals, CurrentUserProvider currentUser,
                                     AgentRunPersistenceService runs, AgentRunStreamCoordinator coordinator) {
        this.proposals = proposals; this.currentUser = currentUser; this.runs = runs; this.coordinator = coordinator;
    }

    public AgentToolProposalView get(long workspaceId, long projectId, String runId, String proposalId) {
        return proposals.getForUser(currentUser.requireUserId(), workspaceId, projectId, runId, proposalId);
    }

    public AgentToolProposalView getPending(long workspaceId, long projectId, String runId) {
        return proposals.getPendingForUser(currentUser.requireUserId(), workspaceId, projectId, runId);
    }

    public AgentToolProposalView decide(long workspaceId, long projectId, String runId, String proposalId,
                                        AgentToolProposalDecision decision) {
        AgentToolProposalDecisionResult result = proposals.decide(currentUser.requireUserId(), workspaceId,
                projectId, runId, proposalId, decision);
        resumeIfNeeded(result);
        return result.proposal();
    }

    public void expireDue() {
        List<AgentToolProposalDecisionResult> expired = proposals.expireDue();
        expired.forEach(this::resumeIfNeeded);
    }

    private void resumeIfNeeded(AgentToolProposalDecisionResult result) {
        if (!result.resumeRequired()) return;
        AgentToolProposalView proposal = result.proposal();
        coordinator.resumeApproval(proposal.workspaceId(), proposal.projectId(),
                runs.get(proposal.workspaceId(), proposal.projectId(), proposal.runId()), proposal.proposalId());
    }
}
