package com.hector.trigger.http;

import com.hector.domain.agent.adapter.port.IRuntimePermissionPort;
import com.hector.domain.agent.adapter.port.IRuntimeStreamPort;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.service.IAgentRunService;
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

    /**
     * 代理 Hexgent 非权威 Stream。
     * curl -N "http://127.0.0.1:8098/api/agent-runs/{runId}/events" -H "Authorization: Bearer 登录令牌"
     */
    @GetMapping(value = "/api/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> stream(
            @PathVariable("runId") String runId) {
        AgentRunEntity run = requireAuthorizedRun(runId);
        InputStream input = runtimeStreamPort.open(run.getRunId());
        StreamingResponseBody body = output -> {
            try (input) {
                input.transferTo(output);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }

    /** 通过 Workspace Run ID 转发 Permission Resolve，不暴露 Hexgent internal runId。 */
    @PostMapping("/api/agent-runs/{runId}/permissions/{requestId}")
    public ResponseEntity<Void> resolvePermission(
            @PathVariable("runId") String runId,
            @PathVariable("requestId") String requestId,
            @RequestBody AgentRunPermissionResolveRequestDTO request) {
        AgentRunEntity run = requireAuthorizedRun(runId);
        if (!"allow".equals(request.getDecision()) && !"deny".equals(request.getDecision())) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        runtimePermissionPort.resolve(run.getRunId(), requestId,
                request.getResponseId(), request.getDecision());
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
