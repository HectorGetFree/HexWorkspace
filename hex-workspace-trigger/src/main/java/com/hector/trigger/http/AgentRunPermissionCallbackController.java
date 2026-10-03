package com.hector.trigger.http;

import com.hector.domain.agent.adapter.port.IBrowserEventPublisher;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.domain.agent.service.IAgentRunPermissionService;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.trigger.api.dto.AgentRunPermissionRequestDTO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;

/** 接收 Hexgent authoritative PermissionRequest，并投影到浏览器实时通道。 */
@RestController
public class AgentRunPermissionCallbackController {
    @Resource private IBrowserEventPublisher browserEventPublisher;
    @Resource private IAgentRunPermissionService permissionService;
    @Value("${hexworkspace.hexgent.lifecycle-callback-token:}") private String callbackToken;

    @PostMapping("/internal/v1/agent-runs/permissions")
    public ResponseEntity<Void> publish(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody AgentRunPermissionRequestDTO request) {
        String expected = "Bearer " + callbackToken;
        if (callbackToken.isBlank() || authorization == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8))) return ResponseEntity.status(401).build();
        if (request.getIntegrationRunId() == null || request.getRequestId() == null
                || request.getToolName() == null || request.getCreatedAt() == null) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        PermissionRequestVO permissionRequest = new PermissionRequestVO(request.getIntegrationRunId(),
                request.getRequestId(), request.getToolName(), request.getDescription(),
                OffsetDateTime.parse(request.getCreatedAt()));
        // 仅首次成功写入收据后通知实时通道，重复 callback 不重复弹窗。
        if (permissionService.receive(permissionRequest)) {
            browserEventPublisher.publishPermission(permissionRequest);
        }
        return ResponseEntity.ok().build();
    }
}
