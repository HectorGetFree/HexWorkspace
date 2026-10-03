package com.hector.trigger.api.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 刷新或重新进入 Conversation 时的最小持久化读模型。 */
@Data
@Builder
public class AgentConversationDetailResponseDTO {
    private String conversationId;
    private Long workspaceId;
    private String title;
    private String createdAt;
    private String updatedAt;
    private List<AgentRunResponseDTO> runs;
    private List<AgentConversationMessageResponseDTO> messages;
}
