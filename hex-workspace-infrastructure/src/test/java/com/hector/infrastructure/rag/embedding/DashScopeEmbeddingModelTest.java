package com.hector.infrastructure.rag.embedding;

import com.hector.domain.knowledge.model.valobj.EmbeddingVectorVO;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DashScope compatible-mode HTTP 协议映射测试，不调用真实 DashScope。 */
class DashScopeEmbeddingModelTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldMapBatchRequestAndIndexedResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("text-embedding-v4"));
            assertTrue(request.contains("第一段"));
            respond(exchange, 200, "{\"data\":[{\"index\":1,\"embedding\":[0.2,0.3]},{\"index\":0,\"embedding\":[0.1,0.4]}]}");
        });
        server.start();

        List<EmbeddingVectorVO> result = model().embedIndexed(List.of("第一段", "第二段"));
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).index());
        assertEquals(List.of(0.1F, 0.4F), result.get(1).vector());
    }

    @Test
    void shouldRejectHttpError() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, 429, "{\"error\":\"rate limit\"}"));
        server.start();
        assertThrows(IllegalStateException.class, () -> model().embedIndexed(List.of("正文")));
    }

    private DashScopeEmbeddingModel model() {
        DashScopeEmbeddingModel model = new DashScopeEmbeddingModel();
        ReflectionTestUtils.setField(model, "apiKey", "test-key");
        ReflectionTestUtils.setField(model, "model", "text-embedding-v4");
        ReflectionTestUtils.setField(model, "dimensions", 2);
        ReflectionTestUtils.setField(model, "timeoutMs", 1_000L);
        ReflectionTestUtils.setField(model, "retryCount", 0);
        ReflectionTestUtils.setField(model, "endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/embeddings");
        return model;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
