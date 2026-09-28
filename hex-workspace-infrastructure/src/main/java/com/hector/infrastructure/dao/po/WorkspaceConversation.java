package com.hector.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Workspace Conversation 表映射对象。 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceConversation {
    private Long id;
    private String conversationId;
    private Long workspaceId;
    private Long userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
