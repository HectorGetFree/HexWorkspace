package com.hector.domain.agent.service;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import java.util.List;

/** Workspace Conversation 业务服务。 */
public interface IWorkspaceConversationService {
    WorkspaceConversationEntity create(Long workspaceId, Long userId, String title);
    WorkspaceConversationEntity require(String conversationId);
    List<WorkspaceConversationEntity> listByUserId(Long userId, int limit);
}
