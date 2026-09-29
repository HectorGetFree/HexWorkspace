package com.hector.infrastructure.agent;

import com.hector.domain.agent.adapter.port.IRuntimeStreamPort;
import com.hector.domain.agent.adapter.port.IRuntimePermissionPort;
import com.hector.domain.agent.adapter.port.RuntimeCommandUnknownException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Hexgent Stream HTTP 代理；只传递外部 StreamEvent，不解析 Core 事件。 */
@Component
public class RuntimeStreamHttpClient implements IRuntimeStreamPort, IRuntimePermissionPort {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${hexworkspace.hexgent.runtime-url:http://127.0.0.1:8000}")
    private String runtimeUrl;

    @Value("${hexworkspace.hexgent.integration-token:}")
    private String integrationToken;

    @Override
    public InputStream open(String workspaceRunId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            runtimeUrl.replaceAll("/+$", "")
                                    + "/internal/v1/runtime/runs/"
                                    + workspaceRunId
                                    + "/events"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Accept", "text/event-stream")
                    .GET();
            if (integrationToken != null && !integrationToken.isBlank()) {
                builder.header("Authorization", "Bearer " + integrationToken);
            }
            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                throw new RuntimeCommandUnknownException(
                        "Hexgent Stream HTTP 状态异常：" + response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new RuntimeCommandUnknownException("Hexgent Stream 网络调用异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeCommandUnknownException("Hexgent Stream 调用被中断", exception);
        }
    }

    @Override
    public void resolve(String workspaceRunId, String requestId, String responseId, String decision) {
        try {
            String json = "{\"responseId\":\"" + escape(responseId)
                    + "\",\"decision\":\"" + escape(decision) + "\"}";
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            runtimeUrl.replaceAll("/+$", "")
                                    + "/internal/v1/runtime/runs/"
                                    + workspaceRunId
                                    + "/permissions/"
                                    + requestId))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (integrationToken != null && !integrationToken.isBlank()) {
                builder.header("Authorization", "Bearer " + integrationToken);
            }
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeCommandUnknownException(
                        "Hexgent Permission HTTP 状态异常：" + response.statusCode());
            }
        } catch (IOException exception) {
            throw new RuntimeCommandUnknownException("Hexgent Permission 网络调用异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeCommandUnknownException("Hexgent Permission 调用被中断", exception);
        }
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
