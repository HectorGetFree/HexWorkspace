package com.hector.infrastructure.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hector.domain.agent.adapter.port.IRuntimeCommandPort;
import com.hector.domain.agent.adapter.port.RuntimeCommandUnknownException;
import com.hector.domain.agent.model.valobj.AgentRunCommandTypeVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandRequestVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Hexgent Runtime HTTP 适配器；协议 DTO 仅存在于此基础设施边界。 */
@Component
public class RuntimeCommandHttpClient implements IRuntimeCommandPort {

    private final HttpClient httpClient;

    @Value("${hexworkspace.hexgent.runtime-url:http://127.0.0.1:8000}")
    private String runtimeUrl;

    @Value("${hexworkspace.hexgent.command-timeout-ms:5000}")
    private long timeoutMs;

    public RuntimeCommandHttpClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                // Uvicorn 不支持 Java HttpClient 的 h2c 升级；内部协议固定使用 HTTP/1.1。
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public RuntimeCommandResultVO send(RuntimeCommandRequestVO request) {
        try {
            JSONObject body = new JSONObject();
            body.put("commandId", request.commandId());
            body.put("type", commandName(request.commandType()));
            body.put("runId", request.runId());
            body.put("conversationId", request.conversationId());
            body.put("issuedAt", request.issuedAt());
            body.put("payload", JSON.parseObject(request.payloadJson()));

            HttpRequest httpRequest = HttpRequest.newBuilder(
                            URI.create(runtimeUrl.replaceAll("/+$", "") + "/internal/v1/runtime/commands"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeCommandUnknownException(
                        "Hexgent Runtime HTTP 状态异常：" + response.statusCode()
                                + " response=" + response.body());
            }
            return parseResult(response.body(), request);
        } catch (RuntimeCommandUnknownException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new RuntimeCommandUnknownException("Hexgent Runtime 网络调用异常", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeCommandUnknownException("Hexgent Runtime 调用被中断", exception);
        } catch (RuntimeException exception) {
            throw new RuntimeCommandUnknownException("Hexgent Runtime 协议响应无效", exception);
        }
    }

    private RuntimeCommandResultVO parseResult(String responseBody, RuntimeCommandRequestVO request) {
        JSONObject body = JSON.parseObject(responseBody);
        String commandId = body.getString("commandId");
        String runId = body.getString("runId");
        String status = body.getString("status");
        if (!request.commandId().equals(commandId) || !request.runId().equals(runId)) {
            throw new RuntimeCommandUnknownException("Hexgent Runtime 响应身份不匹配");
        }
        RuntimeCommandResultVO.Status resultStatus;
        try {
            resultStatus = RuntimeCommandResultVO.Status.valueOf(status);
        } catch (IllegalArgumentException exception) {
            throw new RuntimeCommandUnknownException("Hexgent Runtime 返回未知 Decision", exception);
        }
        return new RuntimeCommandResultVO(
                commandId, runId, resultStatus,
                body.getString("reasonCode"), body.getString("message"));
    }

    private String commandName(AgentRunCommandTypeVO commandType) {
        return switch (commandType) {
            case START_RUN -> "StartRun";
            case CONTINUE_RUN -> "ContinueRun";
            case PAUSE_RUN -> "PauseRun";
            case RESUME_RUN -> "ResumeRun";
            case STEER_RUN -> "SteerRun";
            case CANCEL_RUN -> "CancelRun";
        };
    }
}
