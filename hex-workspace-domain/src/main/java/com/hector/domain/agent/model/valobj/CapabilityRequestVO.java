package com.hector.domain.agent.model.valobj;

/** Hexgent 调用 Workspace Capability 的协议请求。 */
public record CapabilityRequestVO(
        String protocolVersion,
        String runId,
        String callId,
        String capability,
        String argumentsJson) {
}
