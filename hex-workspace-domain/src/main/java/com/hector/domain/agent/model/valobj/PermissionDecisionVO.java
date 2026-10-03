package com.hector.domain.agent.model.valobj;

/** 已持久化的人类 Permission 决定；不代表 Hexgent 已收到该决定。 */
public record PermissionDecisionVO(String workspaceRunId, String requestId,
                                   String decision, String responseId) {
}
