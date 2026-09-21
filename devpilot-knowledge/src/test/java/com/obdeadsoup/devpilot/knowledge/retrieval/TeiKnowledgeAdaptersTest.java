package com.obdeadsoup.devpilot.knowledge.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.knowledge.config.KnowledgeProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeiKnowledgeAdaptersTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsTeiEmbedAndRerankContractsAndParsesScores() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> embedBody = new AtomicReference<>();
        AtomicReference<String> rerankBody = new AtomicReference<>();
        server.createContext("/embed", exchange -> {
            embedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "[[0.25,0.75]]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/rerank", exchange -> {
            rerankBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "[{\"index\":1,\"score\":0.9},{\"index\":0,\"score\":0.2}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            KnowledgeProperties properties = mock(KnowledgeProperties.class);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            when(properties.embeddingEndpoint()).thenReturn(endpoint);
            when(properties.rerankerEndpoint()).thenReturn(endpoint);
            assertThat(new TeiKnowledgeEmbeddingService(properties).embed("query: modules"))
                    .containsExactly(0.25, 0.75);
            assertThat(json.readTree(embedBody.get()).path("inputs").asText())
                    .isEqualTo("query: modules");

            List<KnowledgeSearchHit> candidates = List.of(hit("first", "source A"), hit("second", "source B"));
            List<KnowledgeSearchHit> ranked = new TeiKnowledgeReranker(properties)
                    .rerank("modules", candidates, 2);
            assertThat(json.readTree(rerankBody.get()).path("query").asText()).isEqualTo("modules");
            assertThat(json.readTree(rerankBody.get()).path("texts").get(1).asText()).isEqualTo("source B");
            assertThat(json.readTree(rerankBody.get()).path("raw_scores").asBoolean()).isFalse();
            assertThat(ranked).extracting(KnowledgeSearchHit::chunkId).containsExactly("second", "first");
            assertThat(ranked).extracting(KnowledgeSearchHit::rerankScore).containsExactly(0.9, 0.2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reranksMoreThanTeiBatchLimitAndPreservesGlobalCandidateIndices() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> batchSizes = new ArrayList<>();
        AtomicInteger offset = new AtomicInteger();
        server.createContext("/rerank", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            int size = request.path("texts").size();
            batchSizes.add(size);
            int start = offset.getAndAdd(size);
            var response = json.createArrayNode();
            for (int index = 0; index < size; index++) {
                response.addObject().put("index", index).put("score", start + index);
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            KnowledgeProperties properties = mock(KnowledgeProperties.class);
            when(properties.rerankerEndpoint()).thenReturn("http://127.0.0.1:" + server.getAddress().getPort());
            List<KnowledgeSearchHit> candidates = java.util.stream.IntStream.range(0, 33)
                    .mapToObj(index -> hit("chunk-" + index, "content " + index)).toList();
            List<KnowledgeSearchHit> ranked = new TeiKnowledgeReranker(properties)
                    .rerank("modules", candidates, 5);
            assertThat(batchSizes).containsExactly(16, 16, 1);
            assertThat(ranked).extracting(KnowledgeSearchHit::chunkId)
                    .containsExactly("chunk-32", "chunk-31", "chunk-30", "chunk-29", "chunk-28");
        } finally {
            server.stop(0);
        }
    }

    private KnowledgeSearchHit hit(String id, String content) {
        return new KnowledgeSearchHit(id, "document-1", "README.md", "UPLOAD", null, null,
                0, content, 0.1, 0.2, 0.3, 0);
    }
}
