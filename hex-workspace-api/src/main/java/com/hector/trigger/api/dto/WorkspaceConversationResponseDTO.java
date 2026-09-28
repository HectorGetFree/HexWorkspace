package com.hector.trigger.api.dto;

import lombok.Builder;
import lombok.Data;

/** Workspace Conversation 响应。 */
@Data
@Builder
public class WorkspaceConversationResponseDTO {
    private String conversationId;
    private Long workspaceId;
    private String title;
}
