package com.obdeadsoup.devpilot.agent.persistence.mapper;

import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Optional;

/** AgentRun scoped SQL；终态更新必须同时命中 scope、RUNNING 和 version。 */
@Mapper
public interface AgentRunMapper {
    String COLUMNS = """
            id, run_id AS runId, request_id AS requestId, workspace_id AS workspaceId,
            project_id AS projectId, created_by AS createdBy, status, user_input AS userInput,
            final_output AS finalOutput, failure_kind AS failureKind, started_at AS startedAt,
            finished_at AS finishedAt, created_at AS createdAt, updated_at AS updatedAt, version, deleted
            """;

    @Insert("""
            INSERT INTO dp_agent_run (run_id, request_id, workspace_id, project_id, created_by, status,
                user_input, final_output, failure_kind, started_at, finished_at, version, deleted)
            VALUES (#{run.runId}, #{run.requestId}, #{run.workspaceId}, #{run.projectId}, #{run.createdBy},
                #{run.status}, #{run.userInput}, #{run.finalOutput}, #{run.failureKind}, #{run.startedAt},
                #{run.finishedAt}, #{run.version}, #{run.deleted})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "run.id")
    int insert(@Param("run") AgentRunEntity run);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_run WHERE run_id=#{runId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0")
    Optional<AgentRunEntity> findByScope(@Param("workspaceId") long workspaceId,
                                         @Param("projectId") long projectId,
                                         @Param("runId") String runId);

    @Select("SELECT " + COLUMNS + " FROM dp_agent_run WHERE run_id=#{runId} AND deleted=0")
    Optional<AgentRunEntity> findByRunId(@Param("runId") String runId);

    @Update("""
            UPDATE dp_agent_run SET status='SUCCEEDED', final_output=#{finalOutput}, failure_kind=NULL,
                finished_at=#{finishedAt}, version=version+1
            WHERE run_id=#{runId} AND workspace_id=#{workspaceId} AND project_id=#{projectId}
              AND status='RUNNING' AND version=#{expectedVersion} AND deleted=0
            """)
    int markSucceeded(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                      @Param("runId") String runId, @Param("finalOutput") String finalOutput,
                      @Param("finishedAt") LocalDateTime finishedAt,
                      @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE dp_agent_run SET status='FAILED', final_output=NULL, failure_kind=#{failureKind},
                finished_at=#{finishedAt}, version=version+1
            WHERE run_id=#{runId} AND workspace_id=#{workspaceId} AND project_id=#{projectId}
              AND status='RUNNING' AND version=#{expectedVersion} AND deleted=0
            """)
    int markFailed(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                   @Param("runId") String runId, @Param("failureKind") String failureKind,
                   @Param("finishedAt") LocalDateTime finishedAt,
                   @Param("expectedVersion") long expectedVersion);
}
