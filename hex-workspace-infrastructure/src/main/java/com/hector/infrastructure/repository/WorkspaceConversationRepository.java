package com.hector.infrastructure.repository;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import com.hector.domain.agent.repository.IWorkspaceConversationRepository;
import com.hector.infrastructure.dao.IWorkspaceConversationDao;
import com.hector.infrastructure.dao.po.WorkspaceConversation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Workspace Conversation MySQL 仓储。 */
@Repository
public class WorkspaceConversationRepository implements IWorkspaceConversationRepository {
    @Resource
    private IWorkspaceConversationDao dao;

    @Override
    public WorkspaceConversationEntity save(WorkspaceConversationEntity entity) {
        WorkspaceConversation row = WorkspaceConversation.builder()
                .conversationId(entity.getConversationId()).workspaceId(entity.getWorkspaceId())
                .userId(entity.getUserId()).title(entity.getTitle())
                .createdAt(entity.getCreatedAt()).updatedAt(entity.getUpdatedAt()).build();
        dao.insert(row);
        return toEntity(row);
    }

    @Override
    public Optional<WorkspaceConversationEntity> findByConversationId(String conversationId) {
        return Optional.ofNullable(dao.queryByConversationId(conversationId)).map(this::toEntity);
    }

    @Override
    public Optional<WorkspaceConversationEntity> findByIdForUpdate(String conversationId) {
        return Optional.ofNullable(dao.queryByConversationIdForUpdate(conversationId)).map(this::toEntity);
    }

    private WorkspaceConversationEntity toEntity(WorkspaceConversation row) {
        return WorkspaceConversationEntity.builder().id(row.getId()).conversationId(row.getConversationId())
                .workspaceId(row.getWorkspaceId()).userId(row.getUserId()).title(row.getTitle())
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt()).build();
    }
}
