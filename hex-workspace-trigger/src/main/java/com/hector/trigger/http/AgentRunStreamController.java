package com.hector.trigger.http;

import com.hector.domain.agent.adapter.port.IRuntimePermissionPort;
import com.hector.domain.agent.adapter.port.IRuntimeStreamPort;
import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.service.IAgentRunService;
import com.hector.domain.agent.service.IAgentRunPermissionService;
import com.hector.domain.agent.service.IWorkspaceConversationService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.AgentRunPermissionResolveRequestDTO;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Workspace AgentRun 的 Stream 与 Permission Integration 代理。 */
@RestController
public class AgentRunStreamController {
    @Resource
    private IAgentRunService runService;

    @Resource
    private IWorkspaceConversationService conversationService;

    @Resource
    private IRuntimeStreamPort runtimeStreamPort;

    @Resource
    private IRuntimePermissionPort runtimePermissionPort;

    @Resource
    private IAgentRunPermissionService permissionService;

    @Resource
    private IBrowserEventPublisher browserEventPublisher;

    /**
     * 代理 Hexgent 非权威 Stream。
     * curl -N "http://127.0.0.1:8098/api/agent-runs/{runId}/events" -H "Authorization: Bearer 登录令牌"
     */
    @GetMapping(value = "/api/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable("runId") String runId) {
        AgentRunEntity run = requireAuthorizedRun(runId);
        StreamingResponseBody body = output -> {
            try (IBrowserEventPublisher.Subscription subscription = browserEventPublisher.subscribe(run.getRunId());
                 InputStream input = runtimeStreamPort.open(run.getRunId())) {
                ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
                readerExecutor.submit(() -> forwardRuntimeEvents(run.getRunId(), input));
                try {
                    while (true) {
                        String event = subscription.poll(10, TimeUnit.SECONDS);
                        if (event == null) {
                            // Runtime 静默等待模型或人工确认时，维持 Browser SSE 的传输连接。
                            output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            continue;
                        }
                        if (subscription.isEnd(event)) break;
                        output.write(event.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    readerExecutor.shutdownNow();
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    private void forwardRuntimeEvents(String runId, InputStream input) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder block = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (block.length() > 0) { browserEventPublisher.publishRuntime(runId, block.append("\n").toString()); block.setLength(0); }
                } else block.append(line).append('\n');
            }
            if (block.length() > 0) browserEventPublisher.publishRuntime(runId, block.append("\n").toString());
        } catch (Exception ignored) {
            // 浏览器断开或 Runtime Stream 结束不改变 AgentRun 状态。
        }
    }

    /** 通过 Workspace Run ID 转发 Permission Resolve，不暴露 Hexgent internal runId。 */
    @PostMapping("/api/agent-runs/{runId}/permissions/{requestId}")
    public ResponseEntity<Void> resolvePermission(
            @PathVariable("runId") String runId,
            @PathVariable("requestId") String requestId,
            @RequestBody AgentRunPermissionResolveRequestDTO request) {
        AgentRunEntity run = requireAuthorizedRun(runId);
        if (!"allow".equals(request.getDecision()) && !"deny".equals(request.getDecision())
                || request.getResponseId() == null || request.getResponseId().isBlank()) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        var decision = permissionService.decide(run.getRunId(), requestId,
                request.getDecision(), request.getResponseId());
        runtimePermissionPort.resolve(run.getRunId(), requestId,
                decision.responseId(), decision.decision());
        return ResponseEntity.accepted().build();
    }

    private AgentRunEntity requireAuthorizedRun(String runId) {
        Long userId = SecurityUserUtil.getCurrentUserId();
        AgentRunEntity run = runService.require(runId);
        if (!conversationService.require(run.getConversationId()).getUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        return run;
    }
}
