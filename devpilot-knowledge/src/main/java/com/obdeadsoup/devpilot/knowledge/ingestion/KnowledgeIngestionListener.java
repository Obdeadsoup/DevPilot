package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.obdeadsoup.devpilot.knowledge.application.KnowledgeDocumentUploadedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public final class KnowledgeIngestionListener {
    private final TaskExecutor executor;
    private final KnowledgeIngestionService ingestionService;

    public KnowledgeIngestionListener(@Qualifier("knowledgeTaskExecutor") TaskExecutor executor,
                                      KnowledgeIngestionService ingestionService) {
        this.executor = executor;
        this.ingestionService = ingestionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterUpload(KnowledgeDocumentUploadedEvent event) {
        executor.execute(() -> ingestionService.ingest(event.databaseId()));
    }
}
