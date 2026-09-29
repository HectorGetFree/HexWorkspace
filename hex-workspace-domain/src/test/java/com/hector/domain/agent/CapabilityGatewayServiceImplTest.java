package com.hector.domain.agent;

import com.hector.domain.agent.adapter.port.ICapabilityHandler;
import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.model.valobj.CapabilityRequestVO;
import com.hector.domain.agent.model.valobj.CapabilityResultVO;
import com.hector.domain.agent.service.IAgentRunService;
import com.hector.domain.agent.service.impl.CapabilityGatewayServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityGatewayServiceImplTest {
    @Test
    void rejectsUnknownRunWithStableCode() {
        IAgentRunService runs = mock(IAgentRunService.class);
        when(runs.require("missing")).thenThrow(new IllegalArgumentException());
        CapabilityResultVO result = gateway(runs, new EchoHandler()).invoke(request("missing", "phase5_echo"));
        assertEquals(CapabilityResultVO.Status.DENIED, result.status());
        assertEquals("RUN_NOT_FOUND", result.errorCode());
    }

    @Test
    void requiresRunningAndAllowedCapability() {
        IAgentRunService runs = mock(IAgentRunService.class);
        when(runs.require("paused")).thenReturn(run(AgentRunStatusVO.PAUSED, "[\"phase5_echo\"]"));
        CapabilityResultVO inactive = gateway(runs, new EchoHandler()).invoke(request("paused", "phase5_echo"));
        assertEquals("RUN_NOT_ACTIVE", inactive.errorCode());

        when(runs.require("running")).thenReturn(run(AgentRunStatusVO.RUNNING, "[]"));
        CapabilityResultVO disallowed = gateway(runs, new EchoHandler()).invoke(request("running", "phase5_echo"));
        assertEquals("CAPABILITY_NOT_ALLOWED", disallowed.errorCode());
    }

    @Test
    void dispatchesWithTrustedContextAndIgnoresForgedArguments() {
        IAgentRunService runs = mock(IAgentRunService.class);
        EchoHandler handler = new EchoHandler();
        when(runs.require("r1")).thenReturn(run(AgentRunStatusVO.RUNNING, "[\"phase5_echo\"]"));
        CapabilityResultVO result = gateway(runs, handler).invoke(
                new CapabilityRequestVO("v1", "r1", "c1", "phase5_echo",
                        "{\"userId\":\"FORGED\",\"workspaceId\":\"FORGED\"}"));
        assertEquals(CapabilityResultVO.Status.SUCCEEDED, result.status());
        assertEquals(7L, handler.userId);
        assertEquals(9L, handler.workspaceId);
        assertFalse(result.dataJson().contains("FORGED"));
    }

    @Test
    void returnsStableUnknownAndHandlerErrorCodes() {
        IAgentRunService runs = mock(IAgentRunService.class);
        when(runs.require("r1")).thenReturn(run(AgentRunStatusVO.RUNNING, "[\"phase5_echo\"]"));
        when(runs.require("r1")).thenReturn(run(AgentRunStatusVO.RUNNING, "[\"missing\",\"phase5_echo\"]"));
        CapabilityResultVO unknown = gateway(runs, new EchoHandler()).invoke(request("r1", "missing"));
        assertEquals(CapabilityResultVO.Status.FAILED, unknown.status());
        assertEquals("UNKNOWN_CAPABILITY", unknown.errorCode());
        CapabilityResultVO failed = gateway(runs, new FailingHandler()).invoke(request("r1", "phase5_echo"));
        assertEquals("HANDLER_ERROR", failed.errorCode());
        assertFalse(failed.errorMessage().contains("Exception"));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        CapabilityResultVO result = gateway(mock(IAgentRunService.class), new EchoHandler())
                .invoke(new CapabilityRequestVO("v2", "r1", "c1", "phase5_echo", "{}"));
        assertEquals("INVALID_REQUEST", result.errorCode());
    }

    private CapabilityGatewayServiceImpl gateway(IAgentRunService runs, ICapabilityHandler handler) {
        return new CapabilityGatewayServiceImpl(runs, List.of(handler));
    }

    private CapabilityRequestVO request(String runId, String capability) {
        return new CapabilityRequestVO("v1", runId, "c1", capability, "{}");
    }

    private AgentRunEntity run(AgentRunStatusVO status, String allowedTools) {
        return AgentRunEntity.builder().runId("r1").conversationId("c1")
                .workspaceId(9L).initiatorUserId(7L).status(status)
                .allowedTools(allowedTools).build();
    }

    private static class EchoHandler implements ICapabilityHandler {
        private Long userId;
        private Long workspaceId;

        @Override
        public String capabilityName() {
            return "phase5_echo";
        }

        @Override
        public CapabilityResultVO invoke(com.hector.domain.agent.model.valobj.CapabilityContextVO context,
                                         String argumentsJson) {
            userId = context.initiatorUserId();
            workspaceId = context.workspaceId();
            return new CapabilityResultVO("v1", context.runId(), "", CapabilityResultVO.Status.SUCCEEDED,
                    "{\"userId\":7,\"workspaceId\":9}", null, null);
        }
    }

    private static class FailingHandler extends EchoHandler {
        @Override
        public CapabilityResultVO invoke(com.hector.domain.agent.model.valobj.CapabilityContextVO context,
                                         String argumentsJson) {
            throw new IllegalStateException("secret stack detail");
        }
    }
}
