package com.obdeadsoup.devpilot.knowledge.persistence.mapper;

import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface KnowledgeDocumentMapper {
    String COLUMNS = """
            id, document_id AS documentId, workspace_id AS workspaceId, project_id AS projectId,
            repository_binding_id AS repositoryBindingId, filename, content_type AS contentType,
            size_bytes AS sizeBytes, sha256, object_key AS objectKey, source_type AS sourceType,
            access_scope AS accessScope, status, failure_code AS failureCode, chunk_count AS chunkCount,
            created_by AS createdBy, created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_knowledge_document
              (document_id, workspace_id, project_id, repository_binding_id, filename, content_type,
               size_bytes, sha256, object_key, source_type, access_scope, status, created_by, version, deleted)
            VALUES
              (#{d.documentId}, #{d.workspaceId}, #{d.projectId}, #{d.repositoryBindingId}, #{d.filename},
               #{d.contentType}, #{d.sizeBytes}, #{d.sha256}, #{d.objectKey}, #{d.sourceType},
               #{d.accessScope}, #{d.status}, #{d.createdBy}, 0, 0)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "d.id")
    int insert(@Param("d") KnowledgeDocumentEntity document);

    @Select("SELECT " + COLUMNS + " FROM dp_knowledge_document WHERE id=#{id} AND deleted=0")
    Optional<KnowledgeDocumentEntity> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM dp_knowledge_document WHERE document_id=#{documentId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0")
    Optional<KnowledgeDocumentEntity> findByScope(@Param("workspaceId") long workspaceId,
                                                   @Param("projectId") long projectId,
                                                   @Param("documentId") String documentId);

    @Select("SELECT " + COLUMNS + " FROM dp_knowledge_document WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0 ORDER BY created_at DESC, id DESC")
    List<KnowledgeDocumentEntity> findByProject(@Param("workspaceId") long workspaceId,
                                                 @Param("projectId") long projectId);

    @Update("UPDATE dp_knowledge_document SET status='INGESTING', failure_code=NULL, version=version+1 WHERE id=#{id} AND status IN ('UPLOADED','FAILED') AND deleted=0")
    int markIngesting(@Param("id") long id);

    @Update("UPDATE dp_knowledge_document SET status='UPLOADED', failure_code=NULL, version=version+1 WHERE id=#{id} AND status='FAILED' AND version=#{expectedVersion} AND deleted=0")
    int resetForRetry(@Param("id") long id, @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE dp_knowledge_document SET status='READY', chunk_count=#{chunkCount}, failure_code=NULL, version=version+1 WHERE id=#{id} AND status='INGESTING' AND deleted=0")
    int markReady(@Param("id") long id, @Param("chunkCount") int chunkCount);

    @Update("UPDATE dp_knowledge_document SET status='FAILED', failure_code=#{failureCode}, version=version+1 WHERE id=#{id} AND status='INGESTING' AND deleted=0")
    int markFailed(@Param("id") long id, @Param("failureCode") String failureCode);

    @Select("SELECT COALESCE(MAX(id), 0) * 1000000 + COALESCE(SUM(version), 0) FROM dp_knowledge_document WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND status='READY' AND deleted=0")
    long knowledgeVersion(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);
}
