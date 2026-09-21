package com.obdeadsoup.devpilot.knowledge.persistence.mapper;

import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper {
    String COLUMNS = " " + """
            chunk.id, chunk.chunk_id AS chunkId, chunk.document_id AS documentId,
            chunk.workspace_id AS workspaceId, chunk.project_id AS projectId,
            chunk.repository_binding_id AS repositoryBindingId, chunk.source_file AS sourceFile,
            chunk.source_type AS sourceType, chunk.commit_sha AS commitSha,
            chunk.document_version AS documentVersion, chunk.chunk_index AS chunkIndex,
            chunk.chunk_text AS chunkText, chunk.token_count AS tokenCount,
            chunk.embedding_json AS embeddingJson, chunk.access_scope AS accessScope
            """;

    @Delete("DELETE FROM dp_knowledge_chunk WHERE document_id=#{documentId}")
    int deleteByDocument(@Param("documentId") long documentId);

    @Insert("""
            INSERT INTO dp_knowledge_chunk
              (chunk_id, document_id, workspace_id, project_id, repository_binding_id, source_file,
               source_type, commit_sha, document_version, chunk_index, chunk_text, token_count,
               embedding_json, access_scope)
            VALUES
              (#{c.chunkId}, #{c.documentId}, #{c.workspaceId}, #{c.projectId}, #{c.repositoryBindingId},
               #{c.sourceFile}, #{c.sourceType}, #{c.commitSha}, #{c.documentVersion}, #{c.chunkIndex},
               #{c.chunkText}, #{c.tokenCount}, #{c.embeddingJson}, #{c.accessScope})
            """)
    int insert(@Param("c") KnowledgeChunkEntity chunk);

    /** ACL predicate is pushed into SQL before either retrieval branch scores a chunk. */
    @Select("""
            SELECT """ + COLUMNS + """
            FROM dp_knowledge_chunk chunk
            JOIN dp_knowledge_document document ON document.id=chunk.document_id
            WHERE chunk.workspace_id=#{workspaceId}
              AND chunk.project_id=#{projectId}
              AND chunk.access_scope='PROJECT_MEMBER'
              AND document.status='READY'
              AND document.deleted=0
            ORDER BY chunk.id DESC
            LIMIT #{limit}
            """)
    List<KnowledgeChunkEntity> findRetrievableByProject(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("limit") int limit
    );
}
