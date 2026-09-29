package com.hector.domain.agent.model.valobj;

/** 由 WS 根据 AgentRun 可信数据构造的 Capability 上下文。 */
public record CapabilityContextVO(
        String runId,
        String conversationId,
        Long initiatorUserId,
        Long workspaceId,
        String allowedCapabilities) {
}
