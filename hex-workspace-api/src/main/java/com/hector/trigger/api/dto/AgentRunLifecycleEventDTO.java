package com.hector.trigger.api.dto;

import lombok.Data;

import java.util.Map;

/** Hexgent HTTP callback 的传输对象，不向 Domain 暴露 Hexgent 类型。 */
@Data
public class AgentRunLifecycleEventDTO {
    private String eventId;
    private Long sequence;
    private String workspaceRunId;
    private String conversationId;
    private String type;
    private String occurredAt;
    private String causedByCommandId;
    /** Runtime 投递的结构化事件载荷；进入领域边界后再序列化。 */
    private Map<String, Object> payload;
}
