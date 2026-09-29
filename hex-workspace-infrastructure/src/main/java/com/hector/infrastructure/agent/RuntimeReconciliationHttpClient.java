package com.hector.infrastructure.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.agent.adapter.port.IRuntimeReconciliationPort;
import com.hector.domain.agent.adapter.port.RuntimeCommandUnknownException;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.model.valobj.RuntimeLifecycleStateVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Hexgent durable read API 的 HTTP 适配器。 */
@Component
public class RuntimeReconciliationHttpClient implements IRuntimeReconciliationPort {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    @Value("${hexworkspace.hexgent.runtime-url:http://127.0.0.1:8000}")
    private String runtimeUrl;
    @Value("${hexworkspace.hexgent.integration-token:}")
    private String token;

    @Override
    public RuntimeLifecycleStateVO readState(String runId) {
        JSONObject body = get("/internal/v1/runtime/runs/" + runId + "/state");
        return new RuntimeLifecycleStateVO(
                body.getString("integrationRunId"),
                body.getString("conversationId"),
                body.getString("state"),
                Boolean.TRUE.equals(body.getBoolean("continuable")),
                body.getLong("lastLifecycleSequence"));
    }

    @Override
    public List<LifecycleEventVO> readLifecycleEvents(String runId, long afterSequence) {
        JSONArray values = get("/internal/v1/runtime/runs/" + runId
                + "/lifecycle-events?afterSequence=" + afterSequence)
                .getJSONArray("events");
        List<LifecycleEventVO> result = new ArrayList<>();
        for (Object value : values) {
            JSONObject item = (JSONObject) value;
            result.add(new LifecycleEventVO(
                    item.getString("eventId"),
                    item.getLong("sequence"),
                    item.getString("workspaceRunId"),
                    item.getString("conversationId"),
                    item.getString("type"),
                    OffsetDateTime.parse(item.getString("occurredAt")),
                    item.getString("causedByCommandId"),
                    item.getJSONObject("payload") == null
                            ? "{}" : item.getJSONObject("payload").toJSONString()));
        }
        return result;
    }

    @Override
    public void reconcileCommandEffect(String commandId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            runtimeUrl.replaceAll("/+$", "")
                                    + "/internal/v1/runtime/commands/" + commandId + "/reconcile"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody());
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<Void> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeCommandUnknownException(
                        "Runtime effect 对账失败：" + response.statusCode());
            }
        } catch (Exception exception) {
            if (exception instanceof RuntimeCommandUnknownException known) throw known;
            throw new RuntimeCommandUnknownException("Runtime effect 对账网络调用失败", exception);
        }
    }

    private JSONObject get(String path) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(
                            URI.create(runtimeUrl.replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpResponse<String> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeCommandUnknownException(
                        "Runtime 对账读取失败：" + response.statusCode());
            }
            return JSON.parseObject(response.body());
        } catch (Exception exception) {
            if (exception instanceof RuntimeCommandUnknownException known) throw known;
            throw new RuntimeCommandUnknownException("Runtime 对账网络调用失败", exception);
        }
    }
}
