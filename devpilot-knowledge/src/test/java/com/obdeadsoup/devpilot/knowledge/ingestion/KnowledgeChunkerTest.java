package com.obdeadsoup.devpilot.knowledge.ingestion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkerTest {
    @Test
    void keepsBoundariesOverlapAndMaximumChunkCountDeterministic() {
        KnowledgeChunker chunker = new KnowledgeChunker(20, 5, 2);

        List<String> chunks = chunker.chunk("first paragraph line\n\nsecond paragraph line\n\nthird paragraph line");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo("first paragraph line");
        assertThat(chunks.get(1)).startsWith("ine").contains("second");
    }

    @Test
    void skipsWhitespaceOnlyChunks() {
        assertThat(new KnowledgeChunker(10, 2, 10).chunk("   \n\n   ")).isEmpty();
    }
}
