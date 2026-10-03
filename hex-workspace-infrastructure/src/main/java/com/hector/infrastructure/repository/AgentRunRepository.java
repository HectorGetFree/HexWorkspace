package com.hector.infrastructure.repository;

import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.repository.IAgentRunRepository;
import com.hector.infrastructure.dao.IAgentRunDao;
import com.hector.infrastructure.dao.po.AgentRun;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

/** AgentRun MySQL 仓储。 */
@Repository
public class AgentRunRepository implements IAgentRunRepository {
    @Resource
    private IAgentRunDao dao;

    @Override
    public AgentRunEntity save(AgentRunEntity entity) {
        AgentRun row = AgentRun.builder()
                .runId(entity.getRunId())
                .conversationId(entity.getConversationId())
                .workspaceId(entity.getWorkspaceId())
                .initiatorUserId(entity.getInitiatorUserId())
                .status(entity.getStatus().ordinal())
                .instruction(entity.getInstruction())
                .resourceScope(entity.getResourceScope())
                .allowedTools(entity.getAllowedTools())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .failureCode(entity.getFailureCode())
                .failureMessage(entity.getFailureMessage())
                .lifecycleSequence(entity.getLifecycleSequence())
                .build();
        dao.insert(row);
        return toEntity(row);
    }

    @Override
    public Optional<AgentRunEntity> findByRunId(String runId) {
        return Optional.ofNullable(dao.queryByRunId(runId)).map(this::toEntity);
    }

    @Override
    public Optional<AgentRunEntity> findNonTerminalByConversationId(String conversationId) {
        return Optional.ofNullable(dao.queryNonTerminalByConversationId(conversationId)).map(this::toEntity);
    }

    @Override
    public List<AgentRunEntity> findByConversationId(String conversationId) {
        return dao.queryByConversationId(conversationId).stream().map(this::toEntity).toList();
    }

    @Override
    public boolean markRejected(String runId, String failureCode, String failureMessage) {
        return dao.markRejected(runId, failureCode, failureMessage) == 1;
    }

    private AgentRunEntity toEntity(AgentRun row) {
        return AgentRunEntity.builder()
                .id(row.getId())
                .runId(row.getRunId())
                .conversationId(row.getConversationId())
                .workspaceId(row.getWorkspaceId())
                .initiatorUserId(row.getInitiatorUserId())
                .status(AgentRunStatusVO.values()[row.getStatus()])
                .instruction(row.getInstruction())
                .resourceScope(row.getResourceScope())
                .allowedTools(row.getAllowedTools())
                .version(row.getVersion())
                .createdAt(row.getCreatedAt())
                .startedAt(row.getStartedAt())
                .finishedAt(row.getFinishedAt())
                .failureCode(row.getFailureCode())
                .failureMessage(row.getFailureMessage())
                .lifecycleSequence(row.getLifecycleSequence())
                .build();
    }
}
