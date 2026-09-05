package com.obdeadsoup.devpilot.agent.persistence.mapper;

import com.obdeadsoup.devpilot.agent.persistence.entity.AgentToolProposalEntity;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface AgentToolProposalMapper {
    String COLUMNS = "id,proposal_id AS proposalId,run_id AS runId,actor_id AS actorId," +
            "workspace_id AS workspaceId,project_id AS projectId,tool_call_id AS toolCallId," +
            "tool_name AS toolName,canonical_arguments AS canonicalArguments,payload_hash AS payloadHash," +
            "idempotency_key AS idempotencyKey,status,expires_at AS expiresAt,decision_at AS decisionAt," +
            "executed_at AS executedAt,execution_result AS executionResult,resource_id AS resourceId," +
            "failure_reason AS failureReason,created_at AS createdAt,updated_at AS updatedAt,version";

    @Insert("""
        INSERT INTO dp_agent_tool_proposal
        (proposal_id,run_id,actor_id,workspace_id,project_id,tool_call_id,tool_name,
         canonical_arguments,payload_hash,idempotency_key,status,expires_at,version)
        VALUES (#{p.proposalId},#{p.runId},#{p.actorId},#{p.workspaceId},#{p.projectId},#{p.toolCallId},
         #{p.toolName},#{p.canonicalArguments},#{p.payloadHash},#{p.idempotencyKey},#{p.status},#{p.expiresAt},0)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "p.id")
    int insert(@Param("p") AgentToolProposalEntity proposal);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_tool_proposal WHERE proposal_id=#{proposalId}")
    Optional<AgentToolProposalEntity> findById(@Param("proposalId") String proposalId);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_tool_proposal WHERE run_id=#{runId} AND tool_call_id=#{toolCallId}")
    Optional<AgentToolProposalEntity> findByCall(@Param("runId") String runId, @Param("toolCallId") String toolCallId);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_tool_proposal WHERE run_id=#{runId} AND status IN ('PENDING_APPROVAL','EXECUTING') ORDER BY id DESC LIMIT 1")
    Optional<AgentToolProposalEntity> findPendingByRun(@Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_tool_proposal WHERE proposal_id=#{proposalId} FOR UPDATE")
    Optional<AgentToolProposalEntity> lockById(@Param("proposalId") String proposalId);

    @Update("""
        UPDATE dp_agent_tool_proposal SET status=#{target},decision_at=#{decisionAt},version=version+1
        WHERE proposal_id=#{proposalId} AND status=#{expected} AND version=#{version}
        """)
    int transition(@Param("proposalId") String proposalId, @Param("expected") String expected,
                   @Param("target") String target, @Param("decisionAt") LocalDateTime decisionAt,
                   @Param("version") long version);

    @Update("""
        UPDATE dp_agent_tool_proposal SET status='EXECUTED',execution_result=#{result},resource_id=#{resourceId},
          executed_at=#{executedAt},version=version+1
        WHERE proposal_id=#{proposalId} AND status='EXECUTING' AND version=#{version}
        """)
    int markExecuted(@Param("proposalId") String proposalId, @Param("result") String result,
                     @Param("resourceId") String resourceId, @Param("executedAt") LocalDateTime executedAt,
                     @Param("version") long version);

    @Update("""
        UPDATE dp_agent_tool_proposal SET status='FAILED',failure_reason=#{reason},version=version+1
        WHERE proposal_id=#{proposalId} AND status='EXECUTING' AND version=#{version}
        """)
    int markFailed(@Param("proposalId") String proposalId, @Param("reason") String reason,
                   @Param("version") long version);

    @Select("SELECT proposal_id FROM dp_agent_tool_proposal WHERE status='PENDING_APPROVAL' AND expires_at<=#{now} ORDER BY expires_at LIMIT #{limit}")
    List<String> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
