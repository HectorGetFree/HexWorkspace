package com.hector.infrastructure.agent;

import com.hector.domain.agent.model.valobj.AgentRunCommandTypeVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandRequestVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RuntimeCommandClient 真实 HTTP 序列化和 Ack 映射测试。 */
class RuntimeCommandHttpClientTest {

    private HttpServer server;
    private RuntimeCommandHttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/runtime/commands", exchange -> {
            String response = "{\"commandId\":\"start_run_R1\",\"runId\":\"R1\","
                    + "\"status\":\"ACCEPTED\",\"reasonCode\":null,\"message\":null}";
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        client = new RuntimeCommandHttpClient();
        set(client, "runtimeUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        set(client, "timeoutMs", 2000L);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsRuntimeCommandOverHttpAndMapsAcceptedAck() {
        RuntimeCommandResultVO result = client.send(new RuntimeCommandRequestVO(
                "start_run_R1", AgentRunCommandTypeVO.START_RUN, "R1", "C1",
                "2026-09-28T00:00:00Z", "{\"instruction\":\"执行\"}"));

        assertEquals(RuntimeCommandResultVO.Status.ACCEPTED, result.status());
        assertEquals("start_run_R1", result.commandId());
        assertEquals("R1", result.runId());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
