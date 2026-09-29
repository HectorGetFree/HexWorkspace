package com.hector.trigger.http;

import com.alibaba.fastjson.JSON;
import com.hector.domain.agent.adapter.port.ICapabilityGateway;
import com.hector.domain.agent.model.valobj.CapabilityRequestVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hexgent 到 Workspace 的内部 Capability 入口。 */
@RestController
public class AgentCapabilityController {
    private final ICapabilityGateway gateway;
    @Value("${hexworkspace.hexgent.capability-token:}")
    private String capabilityToken;

    public AgentCapabilityController(ICapabilityGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/internal/agent/capabilities/invoke")
    public ResponseEntity<Map<String, Object>> invoke(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody CapabilityRequestVO request) {
        if (capabilityToken.isBlank() || authorization == null
                || !MessageDigest.isEqual(("Bearer " + capabilityToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        CapabilityResultVO result = gateway.invoke(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", result.protocolVersion());
        body.put("runId", result.runId());
        body.put("callId", result.callId());
        body.put("status", result.status().name());
        if (result.dataJson() != null) {
            body.put("data", JSON.parse(result.dataJson()));
        }
        if (result.errorCode() != null) {
            body.put("error", Map.of("code", result.errorCode(), "message", result.errorMessage()));
        }
        return ResponseEntity.ok(body);
    }
}
