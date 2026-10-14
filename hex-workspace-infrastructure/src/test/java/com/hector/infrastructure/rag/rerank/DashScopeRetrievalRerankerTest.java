package com.hector.infrastructure.rag.rerank;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
            JSONObject body = JSON.parseObject(request);
            assertEquals("qwen3-rerank", body.getString("model"));
            assertEquals("查询内容", body.getJSONObject("input").getString("query"));
            JSONArray documents = body.getJSONObject("input").getJSONArray("documents");
            assertEquals(List.of("标题：文档 A\n章节：第一章 / 小节\n正文：候选正文 A"), documents.toJavaList(String.class));
            assertEquals("configured exact-answer instruction", body.getJSONObject("parameters").getString("instruct"));
            respond(exchange, 200, "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}");
        });
        server.start();

        List<RetrievalRerankScoreVO> scores = reranker(true).rerank("查询内容", List.of(
                new RetrievalRerankCandidateVO(0, "标题：文档 A\n章节：第一章 / 小节\n正文：候选正文 A")));

        assertEquals(1, scores.size());
        assertEquals(0, scores.get(0).index());
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
        ReflectionTestUtils.setField(reranker, "instruct", "configured exact-answer instruction");
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
