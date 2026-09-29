package com.hector.domain.agent.model.valobj;

import java.time.OffsetDateTime;

/** Hexgent 投影到 Workspace 的稳定生命周期事件。 */
public record LifecycleEventVO(String eventId, Long sequence, String workspaceRunId,
                               String conversationId, String type, OffsetDateTime occurredAt,
                               String causedByCommandId, String payloadJson) {
}
