package com.hector.domain.agent.repository;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;

import java.util.Optional;

/** Workspace Conversation 持久化接口。 */
public interface IWorkspaceConversationRepository {
    WorkspaceConversationEntity save(WorkspaceConversationEntity entity);
    Optional<WorkspaceConversationEntity> findByConversationId(String conversationId);
    Optional<WorkspaceConversationEntity> findByIdForUpdate(String conversationId);
}
