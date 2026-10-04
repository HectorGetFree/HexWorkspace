package com.hector.infrastructure.rag.rerank;

import com.hector.domain.knowledge.model.valobj.RetrievalRerankCandidateVO;
import com.hector.domain.knowledge.model.valobj.RetrievalRerankScoreVO;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DashScope Rerank HTTP 协议映射测试，不调用真实云端服务。 */
class DashScopeRetrievalRerankerTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldMapCandidatesAndScores() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/reranks", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("qwen3-rerank"));
            assertTrue(request.contains("查询内容"));
            assertTrue(request.contains("候选正文"));
            respond(exchange, 200, "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}");
        });
        server.start();

        List<RetrievalRerankScoreVO> scores = reranker(true).rerank("查询内容", List.of(
                new RetrievalRerankCandidateVO(0, "候选正文 A"),
                new RetrievalRerankCandidateVO(1, "候选正文 B")));

        assertEquals(2, scores.size());
        assertEquals(1, scores.get(0).index());
    }

    @Test
    void shouldRemainDisabledWithoutApiKey() {
        assertFalse(reranker(false).isEnabled());
    }

    private DashScopeRetrievalReranker reranker(boolean withApiKey) {
        DashScopeRetrievalReranker reranker = new DashScopeRetrievalReranker();
        ReflectionTestUtils.setField(reranker, "enabled", true);
        ReflectionTestUtils.setField(reranker, "apiKey", withApiKey ? "test-key" : "");
        ReflectionTestUtils.setField(reranker, "model", "qwen3-rerank");
        ReflectionTestUtils.setField(reranker, "timeoutMs", 1_000L);
        ReflectionTestUtils.setField(reranker, "instruct", "retrieve answer passages");
        if (server != null) {
            ReflectionTestUtils.setField(reranker, "endpoint",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/reranks");
        }
        return reranker;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
