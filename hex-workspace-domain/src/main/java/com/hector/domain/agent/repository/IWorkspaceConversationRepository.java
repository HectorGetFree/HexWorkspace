package com.hector.domain.agent.repository;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

/** Workspace Conversation 持久化接口。 */
public interface IWorkspaceConversationRepository {
    WorkspaceConversationEntity save(WorkspaceConversationEntity entity);
    Optional<WorkspaceConversationEntity> findByConversationId(String conversationId);
    Optional<WorkspaceConversationEntity> findByIdForUpdate(String conversationId);
    List<WorkspaceConversationEntity> findByUserId(Long userId, int limit);
    void touch(String conversationId, LocalDateTime updatedAt);
}
