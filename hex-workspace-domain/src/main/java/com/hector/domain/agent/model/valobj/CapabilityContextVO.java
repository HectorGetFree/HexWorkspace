package com.hector.domain.agent.model.valobj;

/** 由 WS 根据 AgentRun 可信数据构造的 Capability 上下文。 */
public record CapabilityContextVO(
        String runId,
        String callId,
        String conversationId,
        Long initiatorUserId,
        Long workspaceId,
        String allowedCapabilities) {

    /** 兼容仅用于领域单测的旧构造方式；正式 Gateway 请求必须携带 callId。 */
    public CapabilityContextVO(String runId, String conversationId, Long initiatorUserId,
                               Long workspaceId, String allowedCapabilities) {
        this(runId, null, conversationId, initiatorUserId, workspaceId, allowedCapabilities);
    }
}
