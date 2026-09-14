package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public final class KnowledgeChunker {
    private final int chunkSize;
    private final int overlap;
    private final int maxChunks;

    @Autowired
    public KnowledgeChunker(KnowledgeProperties properties) {
        this(properties.chunkSize(), properties.chunkOverlap(), properties.maxChunksPerDocument());
    }

    KnowledgeChunker(int chunkSize, int overlap, int maxChunks) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxChunks = maxChunks;
    }

    public List<String> chunk(String text) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length() && result.size() < maxChunks) {
            int hardEnd = Math.min(text.length(), start + chunkSize);
            int end = boundary(text, start, hardEnd);
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                result.add(chunk);
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return List.copyOf(result);
    }

    private int boundary(String text, int start, int hardEnd) {
        if (hardEnd == text.length()) return hardEnd;
        int paragraph = text.lastIndexOf("\n\n", hardEnd);
        if (paragraph > start + chunkSize / 2) return paragraph + 2;
        int line = text.lastIndexOf('\n', hardEnd);
        if (line > start + chunkSize / 2) return line + 1;
        int space = text.lastIndexOf(' ', hardEnd);
        return space > start + chunkSize / 2 ? space + 1 : hardEnd;
    }
}
