package com.hector.infrastructure.service;

import com.hector.domain.agent.model.valobj.PermissionDecisionVO;
import com.hector.domain.agent.model.valobj.PermissionRequestVO;
import com.hector.domain.agent.service.IAgentRunPermissionService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.infrastructure.dao.IAgentRunDao;
import com.hector.infrastructure.dao.IAgentRunPermissionRequestDao;
import com.hector.infrastructure.dao.po.AgentRunPermissionRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 通过 MySQL 唯一收据保存 Permission 交互事实及人工决定。 */
@Service
public class AgentRunPermissionServiceImpl implements IAgentRunPermissionService {
    private static final int PENDING = 0;
    private static final int ALLOWED = 1;
    private static final int DENIED = 2;

    @Resource
    private IAgentRunDao agentRunDao;

    @Resource
    private IAgentRunPermissionRequestDao permissionDao;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean receive(PermissionRequestVO request) {
        if (agentRunDao.queryByRunId(request.workspaceRunId()) == null) {
            throw new BusinessException(ResponseCode.AGENT_RUN_NOT_FOUND);
        }
        AgentRunPermissionRequest current = permissionDao.queryByRequestId(request.requestId());
        if (current != null) {
            assertSameRequest(current, request);
            return false;
        }
        AgentRunPermissionRequest receipt = AgentRunPermissionRequest.builder()
                .requestId(request.requestId())
                .runId(request.workspaceRunId())
                .toolName(request.toolName())
                .description(request.description())
                .status(PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        if (permissionDao.insertIgnore(receipt) == 1) return true;

        // 并发 callback 由 UNIQUE(request_id) 收敛；再次读取后验证请求语义。
        current = permissionDao.queryByRequestId(request.requestId());
        if (current == null) throw new BusinessException("A500", "Permission 请求收据保存失败");
        assertSameRequest(current, request);
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public PermissionDecisionVO decide(String workspaceRunId, String requestId,
                                       String decision, String responseId) {
        AgentRunPermissionRequest current = permissionDao.queryByRequestId(requestId);
        if (current == null || !workspaceRunId.equals(current.getRunId())) {
            throw new BusinessException(ResponseCode.AGENT_RUNTIME_REJECTED);
        }
        int requestedStatus = statusOf(decision);
        if (current.getStatus() == PENDING) {
            permissionDao.resolvePending(requestId, workspaceRunId, requestedStatus,
                    responseId, LocalDateTime.now());
            current = permissionDao.queryByRequestId(requestId);
        }
        if (current == null || current.getStatus() != requestedStatus) {
            throw new BusinessException("A409", "Permission 请求已经被其他决定处理");
        }
        // 同决定重试必须复用首次写入的 responseId，才能兼容 Hexgent PendingPermission 幂等语义。
        return new PermissionDecisionVO(workspaceRunId, requestId, decisionOf(current.getStatus()),
                current.getResponseId());
    }

    private void assertSameRequest(AgentRunPermissionRequest current, PermissionRequestVO request) {
        if (!request.workspaceRunId().equals(current.getRunId())
                || !equalsNullable(request.toolName(), current.getToolName())
                || !equalsNullable(request.description(), current.getDescription())) {
            throw new BusinessException("A409", "Permission requestId 对应了不同请求");
        }
    }

    private int statusOf(String decision) {
        return switch (decision) {
            case "allow" -> ALLOWED;
            case "deny" -> DENIED;
            default -> throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        };
    }

    private String decisionOf(int status) {
        return switch (status) {
            case ALLOWED -> "allow";
            case DENIED -> "deny";
            default -> throw new BusinessException("A409", "Permission 请求尚未完成决定");
        };
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
