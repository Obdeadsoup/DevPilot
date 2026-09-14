package com.obdeadsoup.devpilot.knowledge.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeQueryTraceMapper {
    @Insert("""
            INSERT INTO dp_knowledge_query_trace
              (workspace_id, project_id, actor_user_id, original_query, rewritten_query,
               result_chunk_ids, knowledge_version)
            VALUES
              (#{workspaceId}, #{projectId}, #{actorUserId}, #{originalQuery}, #{rewrittenQuery},
               CAST(#{resultChunkIdsJson} AS JSON), #{knowledgeVersion})
            """)
    int insert(@Param("workspaceId") long workspaceId,
               @Param("projectId") long projectId,
               @Param("actorUserId") long actorUserId,
               @Param("originalQuery") String originalQuery,
               @Param("rewrittenQuery") String rewrittenQuery,
               @Param("resultChunkIdsJson") String resultChunkIdsJson,
               @Param("knowledgeVersion") long knowledgeVersion);
}
