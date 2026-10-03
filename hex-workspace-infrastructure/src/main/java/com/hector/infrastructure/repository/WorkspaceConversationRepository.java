package com.hector.infrastructure.repository;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import com.hector.domain.agent.repository.IWorkspaceConversationRepository;
import com.hector.infrastructure.dao.IWorkspaceConversationDao;
import com.hector.infrastructure.dao.po.WorkspaceConversation;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

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

    @Override
    public List<WorkspaceConversationEntity> findByUserId(Long userId, int limit) {
        return dao.queryByUserId(userId, limit).stream().map(this::toEntity).toList();
    }

    @Override
    public void touch(String conversationId, LocalDateTime updatedAt) {
        dao.touch(conversationId, updatedAt);
    }

    private WorkspaceConversationEntity toEntity(WorkspaceConversation row) {
        return WorkspaceConversationEntity.builder().id(row.getId()).conversationId(row.getConversationId())
                .workspaceId(row.getWorkspaceId()).userId(row.getUserId()).title(row.getTitle())
                .createdAt(row.getCreatedAt()).updatedAt(row.getUpdatedAt()).build();
    }
}
