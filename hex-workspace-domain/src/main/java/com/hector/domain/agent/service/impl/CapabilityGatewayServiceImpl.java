package com.hector.domain.agent.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.hector.domain.agent.adapter.port.ICapabilityGateway;
import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.model.valobj.CapabilityContextVO;
import com.hector.domain.agent.model.valobj.CapabilityRequestVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.service.IAgentRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** 校验 AgentRun 边界并分发 Capability 的 Gateway。 */
@Service
public class CapabilityGatewayServiceImpl implements ICapabilityGateway {
    private static final Logger log = LoggerFactory.getLogger(CapabilityGatewayServiceImpl.class);

    private final IAgentRunService runService;
    private final List<ICapabilityHandler> handlers;

    public CapabilityGatewayServiceImpl(IAgentRunService runService, List<ICapabilityHandler> handlers) {
        this.runService = runService;
        this.handlers = handlers;
    }

    @Override
    public CapabilityResultVO invoke(CapabilityRequestVO request) {
        if (!"v1".equals(request.protocolVersion()) || blank(request.runId())
                || blank(request.callId()) || blank(request.capability())) {
            return CapabilityResultVO.denied(request.runId(), request.callId(),
                    "INVALID_REQUEST", "Capability request is invalid");
        }
        AgentRunEntity run;
        try {
            run = runService.require(request.runId());
        } catch (RuntimeException exception) {
            return CapabilityResultVO.denied(request.runId(), request.callId(),
                    "RUN_NOT_FOUND", "AgentRun not found");
        }
        if (run.getStatus() != AgentRunStatusVO.RUNNING) {
            return CapabilityResultVO.denied(request.runId(), request.callId(),
                    "RUN_NOT_ACTIVE", "AgentRun is not running");
        }
        if (!allowed(run.getAllowedTools(), request.capability())) {
            return CapabilityResultVO.denied(request.runId(), request.callId(),
                    "CAPABILITY_NOT_ALLOWED", "Capability is outside the run boundary");
        }
        ICapabilityHandler handler = handlers.stream()
                .filter(item -> item.capabilityName().equals(request.capability()))
                .findFirst().orElse(null);
        if (handler == null) {
            return CapabilityResultVO.failed(request.runId(), request.callId(),
                    "UNKNOWN_CAPABILITY", "Capability is not registered");
        }
        try {
            CapabilityResultVO result = handler.invoke(new CapabilityContextVO(
                    run.getRunId(), request.callId(), run.getConversationId(), run.getInitiatorUserId(),
                    run.getWorkspaceId(), run.getAllowedTools()), request.argumentsJson());
            return new CapabilityResultVO(
                    "v1", request.runId(), request.callId(), result.status(), result.dataJson(),
                    result.errorCode(), result.errorMessage());
        } catch (RuntimeException exception) {
            log.error("Capability Handler 执行异常 runId:{} callId:{} capability:{}",
                    request.runId(), request.callId(), request.capability(), exception);
            return new CapabilityResultVO("v1", request.runId(), request.callId(),
                    CapabilityResultVO.Status.FAILED, null, "HANDLER_ERROR", "Capability handler failed");
        }
    }

    private boolean allowed(String raw, String capability) {
        if (blank(raw)) return false;
        try {
            JSONArray values = JSON.parseArray(raw);
            return values != null && values.stream().anyMatch(value -> capability.equals(String.valueOf(value)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
