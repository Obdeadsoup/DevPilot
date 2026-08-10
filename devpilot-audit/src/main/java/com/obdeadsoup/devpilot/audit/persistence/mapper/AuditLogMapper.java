package com.obdeadsoup.devpilot.audit.persistence.mapper;

import com.obdeadsoup.devpilot.audit.persistence.entity.AuditInsert;
import com.obdeadsoup.devpilot.audit.persistence.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Append-only Audit Mapper：只暴露 INSERT 与带 scope 的 SELECT，不提供 UPDATE/DELETE。 */
@Mapper
public interface AuditLogMapper {
    String COLUMNS = """
            id,actor_type AS actorType,actor_user_id AS actorUserId,workspace_id AS workspaceId,
            project_id AS projectId,action_type AS actionType,resource_type AS resourceType,
            resource_id AS resourceId,result,reason,error_code AS errorCode,request_id AS requestId,
            correlation_id AS correlationId,CAST(metadata_json AS CHAR) AS metadataJson,
            occurred_at AS occurredAt,created_at AS createdAt
            """;

    @Insert("""
            INSERT INTO dp_audit_log(actor_type,actor_user_id,workspace_id,project_id,action_type,resource_type,
              resource_id,result,reason,error_code,request_id,correlation_id,metadata_json,occurred_at)
            VALUES(#{row.command.actorType},#{row.command.actorUserId},#{row.command.workspaceId},#{row.command.projectId},
              #{row.command.actionType},#{row.command.resourceType},#{row.command.resourceId},#{row.command.result},
              #{row.command.reason},#{row.command.errorCode},#{row.command.requestId},#{row.command.correlationId},
              CAST(#{row.metadataJson} AS JSON),#{row.command.occurredAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "row.id")
    int insert(@Param("row") AuditInsert row);

    @Select("<script>SELECT " + COLUMNS + """
            FROM dp_audit_log
            WHERE workspace_id=#{workspaceId}
            <if test='projectId != null'>AND project_id=#{projectId}</if>
            <if test='actorUserId != null'>AND actor_user_id=#{actorUserId}</if>
            <if test='actionType != null'>AND action_type=#{actionType}</if>
            <if test='resourceType != null'>AND resource_type=#{resourceType}</if>
            <if test='result != null'>AND result=#{result}</if>
            <if test='occurredFrom != null'>AND occurred_at &gt;= #{occurredFrom}</if>
            <if test='occurredTo != null'>AND occurred_at &lt;= #{occurredTo}</if>
            ORDER BY occurred_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>
            """)
    List<AuditLogEntity> findPage(@Param("workspaceId") long workspaceId, @Param("projectId") Long projectId,
                                  @Param("actorUserId") Long actorUserId, @Param("actionType") String actionType,
                                  @Param("resourceType") String resourceType, @Param("result") String result,
                                  @Param("occurredFrom") LocalDateTime occurredFrom,
                                  @Param("occurredTo") LocalDateTime occurredTo,
                                  @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>SELECT COUNT(*) FROM dp_audit_log WHERE workspace_id=#{workspaceId}
            <if test='projectId != null'>AND project_id=#{projectId}</if>
            <if test='actorUserId != null'>AND actor_user_id=#{actorUserId}</if>
            <if test='actionType != null'>AND action_type=#{actionType}</if>
            <if test='resourceType != null'>AND resource_type=#{resourceType}</if>
            <if test='result != null'>AND result=#{result}</if>
            <if test='occurredFrom != null'>AND occurred_at &gt;= #{occurredFrom}</if>
            <if test='occurredTo != null'>AND occurred_at &lt;= #{occurredTo}</if></script>
            """)
    long count(@Param("workspaceId") long workspaceId, @Param("projectId") Long projectId,
               @Param("actorUserId") Long actorUserId, @Param("actionType") String actionType,
               @Param("resourceType") String resourceType, @Param("result") String result,
               @Param("occurredFrom") LocalDateTime occurredFrom, @Param("occurredTo") LocalDateTime occurredTo);

    @Select("SELECT " + COLUMNS + " FROM dp_audit_log WHERE workspace_id=#{workspaceId} AND id=#{auditId} " +
            "AND (#{projectId} IS NULL OR project_id=#{projectId})")
    Optional<AuditLogEntity> findByScope(@Param("workspaceId") long workspaceId,
                                         @Param("projectId") Long projectId,
                                         @Param("auditId") long auditId);
}
