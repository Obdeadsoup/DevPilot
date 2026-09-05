package com.obdeadsoup.devpilot.agent.api.dto;

import com.obdeadsoup.devpilot.agent.application.proposal.AgentToolProposalDecision;
import jakarta.validation.constraints.NotNull;

/** 客户端只提交裁决；tool/arguments 永远从 Java 固化 Proposal 加载。 */
public record AgentToolProposalDecisionRequest(@NotNull AgentToolProposalDecision decision) { }
