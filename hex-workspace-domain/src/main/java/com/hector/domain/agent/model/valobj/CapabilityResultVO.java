package com.hector.domain.agent.model.valobj;

/** Workspace Capability 的标准响应。 */
public record CapabilityResultVO(
        String protocolVersion,
        String runId,
        String callId,
        Status status,
        String dataJson,
        String errorCode,
        String errorMessage) {
    public enum Status { SUCCEEDED, FAILED, DENIED }

    public static CapabilityResultVO denied(String runId, String callId, String code, String message) {
        return new CapabilityResultVO("v1", runId, callId, Status.DENIED, null, code, message);
    }

    public static CapabilityResultVO failed(String runId, String callId, String code, String message) {
        return new CapabilityResultVO("v1", runId, callId, Status.FAILED, null, code, message);
    }
}
