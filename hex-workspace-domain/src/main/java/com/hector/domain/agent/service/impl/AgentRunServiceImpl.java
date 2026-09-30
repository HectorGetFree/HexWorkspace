package com.hector.domain.agent.service.impl;

import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.adapter.port.IRuntimeCommandPort;
import com.hector.domain.agent.model.valobj.AgentRunCommandTypeVO;
import com.hector.domain.agent.model.valobj.AgentRunStatusVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandRequestVO;
import com.hector.domain.agent.model.valobj.RuntimeCommandResultVO;
import com.hector.domain.agent.repository.IAgentRunRepository;
import com.hector.domain.agent.repository.IWorkspaceConversationRepository;
import com.hector.domain.agent.service.IAgentRunService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** AgentRun 业务服务；创建时锁定 Conversation 行并检查非终态 Run。 */
@Service
public class AgentRunServiceImpl implements IAgentRunService {
    /**
     * 仅由服务端部署配置控制的新 Session 权限模式；不能由前端请求提升权限。
     */
    @Value("${hexworkspace.agent.permission-mode:default}")
    private String runtimePermissionMode = "default";

    @Resource
    private IWorkspaceConversationRepository conversationRepository;

    @Resource
    private IAgentRunRepository runRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IRuntimeCommandPort runtimeCommandPort;

    @Override
    public AgentRunEntity start(String conversationId, Long userId, String instruction,
                                String resourceScope, String allowedTools) {
        AgentRunEntity result = transactionTemplate.execute(status -> {
            var conversation = conversationRepository.findByIdForUpdate(conversationId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.ILLEGAL_PARAMETER));
            if (!conversation.getUserId().equals(userId)) {
                throw new BusinessException(ResponseCode.FORBIDDEN);
            }
            if (runRepository.findNonTerminalByConversationId(conversationId).isPresent()) {
                throw new BusinessException(ResponseCode.AGENT_RUN_CONFLICT);
            }
            LocalDateTime now = LocalDateTime.now();
            return runRepository.save(AgentRunEntity.builder()
                    .runId("run_" + UUID.randomUUID().toString().replace("-", ""))
                    .conversationId(conversationId)
                    .workspaceId(conversation.getWorkspaceId())
                    .initiatorUserId(userId)
                    .status(AgentRunStatusVO.PENDING)
                    .instruction(instruction)
                    .resourceScope(resourceScope)
                    .allowedTools(allowedTools)
                    .version(0L)
                    .lifecycleSequence(0L)
                    .createdAt(now)
                    .build());
        });
        if (result == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        return result;
    }

    @Override
    public AgentRunEntity require(String runId) {
        return runRepository.findByRunId(runId)
                .orElseThrow(() -> new BusinessException(ResponseCode.ILLEGAL_PARAMETER));
    }

    @Override
    public AgentRunEntity startAndDispatch(String conversationId, Long userId, String instruction,
                                           String resourceScope, String allowedTools) {
        // 1. 先提交 PENDING 业务事实；后续网络未知时保留该 Run，禁止创建第二个 Run。
        AgentRunEntity run = transactionTemplate.execute(status -> {
            AgentRunEntity existing = runRepository.findNonTerminalByConversationId(conversationId).orElse(null);
            if (existing != null) {
                if (!existing.getInitiatorUserId().equals(userId)
                        || !existing.getInstruction().equals(instruction)
                        || existing.getStatus() != AgentRunStatusVO.PENDING) {
                    throw new BusinessException(ResponseCode.AGENT_RUN_CONFLICT);
                }
                return existing;
            }
            return start(conversationId, userId, instruction, resourceScope, allowedTools);
        });
        if (run == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        RuntimeCommandResultVO result = runtimeCommandPort.send(new RuntimeCommandRequestVO(
                "start_" + run.getRunId(), AgentRunCommandTypeVO.START_RUN, run.getRunId(),
                conversationId, OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "{\"instruction\":" + quote(instruction)
                        + ",\"permissionMode\":" + quote(runtimePermissionMode) + "}"));
        if (!result.accepted()) {
            runRepository.markRejected(run.getRunId(), result.reasonCode(), result.message());
            throw new BusinessException(ResponseCode.AGENT_RUNTIME_REJECTED);
        }
        return require(run.getRunId());
    }

    @Override
    public RuntimeCommandResultVO dispatch(String conversationId, String runId, Long userId,
                                           AgentRunCommandTypeVO commandType, String payloadJson) {
        AgentRunEntity run = require(runId);
        if (!run.getConversationId().equals(conversationId)
                || !run.getInitiatorUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        validateCommandState(run.getStatus(), commandType);
        RuntimeCommandResultVO result = runtimeCommandPort.send(new RuntimeCommandRequestVO(
                UUID.randomUUID().toString(), commandType, runId, conversationId,
                OffsetDateTime.now(ZoneOffset.UTC).toString(), payloadJson));
        if (!result.accepted()) {
            runRepository.markRejected(runId, result.reasonCode(), result.message());
            throw new BusinessException(ResponseCode.AGENT_RUNTIME_REJECTED);
        }
        return result;
    }

    private void validateCommandState(AgentRunStatusVO status, AgentRunCommandTypeVO commandType) {
        boolean valid = switch (commandType) {
            case CONTINUE_RUN -> status == AgentRunStatusVO.INCOMPLETE;
            case PAUSE_RUN -> status == AgentRunStatusVO.RUNNING;
            case RESUME_RUN -> status == AgentRunStatusVO.PAUSED;
            case STEER_RUN -> status == AgentRunStatusVO.RUNNING;
            case CANCEL_RUN -> status.isNonTerminal();
            case START_RUN -> false;
        };
        if (!valid) {
            throw new BusinessException(ResponseCode.AGENT_RUN_STATE_INVALID);
        }
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
