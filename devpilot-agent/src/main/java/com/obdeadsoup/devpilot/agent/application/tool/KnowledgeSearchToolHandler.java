package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeRetrievalResult;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeRetrievalService;
import com.obdeadsoup.devpilot.knowledge.retrieval.KnowledgeSearchHit;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Run-bound knowledge retrieval; actor and project scope always come from the persisted Java run. */
@Component
public final class KnowledgeSearchToolHandler implements AgentReadToolHandler {
    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeSearchToolHandler(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public AgentToolName name() {
        return AgentToolName.KNOWLEDGE_SEARCH;
    }

    @Override
    public Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments) {
        if (arguments == null || arguments.keySet().stream().anyMatch(key -> !SetHolder.ALLOWED.contains(key))) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        Object queryValue = arguments.get("query");
        if (!(queryValue instanceof String query) || query.isBlank() || query.strip().length() > 2_000) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        Integer topK = null;
        if (arguments.containsKey("topK")) {
            Object value = arguments.get("topK");
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue()
                    || number.intValue() < 1 || number.intValue() > 10) {
                throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
            }
            topK = number.intValue();
        }
        KnowledgeRetrievalResult result = retrievalService.searchForActor(context.createdBy(), context.workspaceId(),
                context.projectId(), query, List.of(), topK);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rewrittenQuery", result.rewrittenQuery());
        response.put("knowledgeVersion", result.knowledgeVersion());
        response.put("sources", result.hits().stream().map(this::source).toList());
        response.put("external_untrusted_content", true);
        return response;
    }

    private Map<String, Object> source(KnowledgeSearchHit hit) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("chunkId", hit.chunkId());
        source.put("sourceFile", hit.sourceFile());
        source.put("chunkIndex", hit.chunkIndex());
        source.put("content", AgentToolArguments.bounded(hit.content(), 2_000));
        source.put("relevanceScore", hit.rerankScore());
        return source;
    }

    private static final class SetHolder {
        private static final java.util.Set<String> ALLOWED = java.util.Set.of("query", "topK");
    }
}
