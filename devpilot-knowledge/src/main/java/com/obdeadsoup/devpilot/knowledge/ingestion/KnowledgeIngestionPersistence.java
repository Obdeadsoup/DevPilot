package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeChunkEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.entity.KnowledgeDocumentEntity;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeChunkMapper;
import com.obdeadsoup.devpilot.knowledge.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class KnowledgeIngestionPersistence {
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeChunkMapper chunks;

    public KnowledgeIngestionPersistence(KnowledgeDocumentMapper documents, KnowledgeChunkMapper chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    @Transactional
    public Optional<KnowledgeDocumentEntity> begin(long id) {
        if (documents.markIngesting(id) != 1) return Optional.empty();
        return documents.findById(id);
    }

    @Transactional
    public void complete(long id, List<KnowledgeChunkEntity> replacements) {
        chunks.deleteByDocument(id);
        replacements.forEach(chunks::insert);
        if (documents.markReady(id, replacements.size()) != 1) {
            throw new IllegalStateException("knowledge document left INGESTING state");
        }
    }

    @Transactional
    public void fail(long id, String code) {
        documents.markFailed(id, code);
    }
}
