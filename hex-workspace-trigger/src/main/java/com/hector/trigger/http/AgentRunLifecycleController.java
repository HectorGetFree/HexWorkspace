package com.hector.trigger.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hector.domain.agent.model.valobj.LifecycleEventVO;
import com.hector.domain.agent.service.IAgentRunLifecycleService;
import com.hector.trigger.api.dto.AgentRunLifecycleEventDTO;
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

/** 接收 Hexgent durable lifecycle facts 的 HTTP 投影入口。 */
@RestController
public class AgentRunLifecycleController {
    @Resource
    private IAgentRunLifecycleService lifecycleService;
    @Resource
    private ObjectMapper objectMapper;
    @Value("${hexworkspace.hexgent.lifecycle-callback-token:}")
    private String callbackToken;

    @PostMapping("/internal/v1/agent-runs/lifecycle")
    public ResponseEntity<Void> project(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AgentRunLifecycleEventDTO request) {
        String expected = "Bearer " + callbackToken;
        if (callbackToken.isBlank() || authorization == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        try {
            String payloadJson = request.getPayload() == null
                    ? "{}"
                    : objectMapper.writeValueAsString(request.getPayload());
            lifecycleService.project(new LifecycleEventVO(
                    request.getEventId(),
                    request.getSequence(),
                    request.getWorkspaceRunId(),
                    request.getConversationId(),
                    request.getType(),
                    OffsetDateTime.parse(request.getOccurredAt()),
                    request.getCausedByCommandId(),
                    payloadJson));
            return ResponseEntity.ok().build();
        } catch (JsonProcessingException exception) {
            return ResponseEntity.badRequest().build();
        }
    }
}
