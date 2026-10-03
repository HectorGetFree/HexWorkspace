package com.hector.domain.agent.service.impl;

import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import com.hector.domain.agent.repository.IWorkspaceConversationRepository;
import com.hector.domain.agent.service.IWorkspaceConversationService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/** Conversation 业务服务。 */
@Service
public class WorkspaceConversationServiceImpl implements IWorkspaceConversationService {
    @Resource
    private IWorkspaceConversationRepository repository;

    @Override
    public WorkspaceConversationEntity create(Long workspaceId, Long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        return repository.save(WorkspaceConversationEntity.builder()
                .conversationId("conversation_" + UUID.randomUUID().toString().replace("-", ""))
                .workspaceId(workspaceId)
                .userId(userId)
                .title(title)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    public WorkspaceConversationEntity require(String conversationId) {
        return repository.findByConversationId(conversationId)
                .orElseThrow(() -> new BusinessException(ResponseCode.ILLEGAL_PARAMETER));
    }

    @Override
    public List<WorkspaceConversationEntity> listByUserId(Long userId, int limit) {
        return repository.findByUserId(userId, limit);
    }
}
