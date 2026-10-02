package com.hector.trigger.http;

import com.hector.domain.agent.model.entity.AgentRunEntity;
import com.hector.domain.agent.model.entity.WorkspaceConversationEntity;
import com.hector.domain.agent.adapter.port.RuntimeCommandUnknownException;
import com.hector.domain.agent.adapter.port.IRuntimeConversationHistoryPort;
import com.hector.domain.agent.model.valobj.RuntimeConversationMessageVO;
import com.hector.domain.agent.model.valobj.AgentRunCommandTypeVO;
import com.hector.domain.agent.service.IAgentRunService;
import com.hector.domain.agent.service.IWorkspaceConversationService;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.AgentRunCommandRequestDTO;
import com.hector.trigger.api.dto.AgentRunResponseDTO;
import com.hector.trigger.api.dto.AgentRunStartRequestDTO;
import com.hector.trigger.api.dto.WorkspaceConversationCreateRequestDTO;
import com.hector.trigger.api.dto.WorkspaceConversationResponseDTO;
import com.hector.trigger.api.dto.AgentConversationDetailResponseDTO;
import com.hector.trigger.api.dto.AgentConversationSummaryResponseDTO;
import com.hector.trigger.api.dto.AgentConversationMessageResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Workspace AgentRun 最小用户侧接口；Controller 不直接调用 Hexgent Client。 */
@RestController
public class AgentRunController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(AgentRunController.class);

    @Resource
    private IWorkspaceService workspaceService;
    @Resource
    private IWorkspaceConversationService conversationService;
    @Resource
    private IAgentRunService runService;
    @Resource
    private IRuntimeConversationHistoryPort runtimeConversationHistoryPort;

    /** 返回当前用户的最近 Conversation；浏览器 History 只把 Pinia 当作缓存。 */
    @GetMapping("/api/v1/conversations")
    public Response<List<AgentConversationSummaryResponseDTO>> listConversations() {
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            return success(conversationService.listByUserId(userId, 50).stream()
                    .map(conversation -> {
                        List<AgentRunEntity> runs = runService.listByConversationId(conversation.getConversationId());
                        AgentRunEntity latest = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                        return AgentConversationSummaryResponseDTO.builder()
                                .conversationId(conversation.getConversationId())
                                .workspaceId(conversation.getWorkspaceId())
                                .title(latest != null && latest.getInstruction() != null
                                        && !latest.getInstruction().isBlank()
                                        ? latest.getInstruction()
                                        : conversation.getTitle())
                                .updatedAt(conversation.getUpdatedAt().toString())
                                .latestRunStatus(latest == null ? null : latest.getStatus().name())
                                .build();
                    }).toList());
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询Agent Conversation History系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /** 按当前用户授权读取 Conversation、Run 与 Hexgent durable message projection。 */
    @GetMapping("/api/v1/conversations/{conversationId}")
    public Response<AgentConversationDetailResponseDTO> getConversation(
            @PathVariable("conversationId") String conversationId) {
        try {
            WorkspaceConversationEntity conversation = requireConversation(
                    conversationId, SecurityUserUtil.getCurrentUserId());
            List<AgentRunEntity> runs = runService.listByConversationId(conversationId);
            List<AgentConversationMessageResponseDTO> messages = List.of();
            if (!runs.isEmpty()) {
                AgentRunEntity latest = runs.get(runs.size() - 1);
                messages = runtimeConversationHistoryPort.readMessages(latest.getRunId()).stream()
                        .map(this::toMessageResponse).toList();
            }
            return success(AgentConversationDetailResponseDTO.builder()
                    .conversationId(conversation.getConversationId())
                    .workspaceId(conversation.getWorkspaceId())
                    .title(conversation.getTitle())
                    .createdAt(conversation.getCreatedAt().toString())
                    .updatedAt(conversation.getUpdatedAt().toString())
                    .runs(runs.stream().map(this::toRunResponse).toList())
                    .messages(messages)
                    .build());
        } catch (RuntimeCommandUnknownException exception) {
            log.error("读取Agent Conversation Transcript失败 conversationId:{}", conversationId, exception);
            return failure(ResponseCode.AGENT_RUNTIME_UNAVAILABLE);
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("读取Agent Conversation系统异常 conversationId:{}", conversationId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 创建 Workspace Conversation。
     * curl -X POST "http://127.0.0.1:8098/api/v1/workspaces/1/conversations" \
     *   -H "Authorization: Bearer 登录令牌" -H "Content-Type: application/json" \
     *   -d '{"data":{"title":"资料助手"}}'
     */
    @PostMapping("/api/v1/workspaces/{workspaceId}/conversations")
    public Response<WorkspaceConversationResponseDTO> createConversation(
            @PathVariable("workspaceId") Long workspaceId,
            @RequestBody Request<WorkspaceConversationCreateRequestDTO> request) {
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            log.info("创建Agent Conversation开始 workspaceId:{} userId:{}", workspaceId, userId);
            workspaceService.assertOwner(workspaceId, userId);
            WorkspaceConversationCreateRequestDTO data = requireData(request);
            WorkspaceConversationEntity conversation = conversationService.create(
                    workspaceId, userId, data.getTitle());
            log.info("创建Agent Conversation完成 workspaceId:{} conversationId:{}",
                    workspaceId, conversation.getConversationId());
            return success(toConversationResponse(conversation));
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("创建Agent Conversation系统异常 workspaceId:{}", workspaceId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 创建 PENDING AgentRun 并发送 StartRun；HTTP 超时不会删除该 Run。
     * curl -X POST "http://127.0.0.1:8098/api/v1/conversations/{conversationId}/runs" \
     *   -H "Authorization: Bearer 登录令牌" -H "Content-Type: application/json" \
     *   -d '{"data":{"instruction":"整理资料"}}'
     */
    @PostMapping("/api/v1/conversations/{conversationId}/runs")
    public Response<AgentRunResponseDTO> startRun(
            @PathVariable("conversationId") String conversationId,
            @RequestBody Request<AgentRunStartRequestDTO> request) {
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            requireConversation(conversationId, userId);
            AgentRunStartRequestDTO data = requireData(request);
            AgentRunEntity run = runService.startAndDispatch(
                    conversationId, userId, data.getInstruction(),
                    data.getResourceScope(), data.getAllowedTools());
            log.info("StartRun完成 conversationId:{} runId:{} status:{}",
                    conversationId, run.getRunId(), run.getStatus());
            return success(toRunResponse(run));
        } catch (RuntimeCommandUnknownException exception) {
            log.error("StartRun Runtime结果未知 conversationId:{}", conversationId, exception);
            return failure(ResponseCode.AGENT_RUNTIME_UNAVAILABLE);
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("StartRun系统异常 conversationId:{}", conversationId, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 发送 Phase 1 控制命令；ACK 不会投影未来 Lifecycle 状态。
     * curl -X POST "http://127.0.0.1:8098/api/v1/conversations/{conversationId}/runs/{runId}/cancel" \
     *   -H "Authorization: Bearer 登录令牌"
     */
    @PostMapping("/api/v1/conversations/{conversationId}/runs/{runId}/{command}")
    public Response<AgentRunResponseDTO> command(
            @PathVariable("conversationId") String conversationId,
            @PathVariable("runId") String runId,
            @PathVariable("command") String command,
            @RequestBody(required = false) Request<AgentRunCommandRequestDTO> request) {
        try {
            Long userId = SecurityUserUtil.getCurrentUserId();
            requireConversation(conversationId, userId);
            AgentRunCommandTypeVO commandType = parseCommand(command);
            String instruction = request == null || request.getData() == null
                    ? "" : request.getData().getInstruction();
            runService.dispatch(
                    conversationId, runId, userId, commandType,
                    "{\"instruction\":\"" + escape(instruction) + "\"}");
            AgentRunEntity run = runService.require(runId);
            log.info("AgentRun命令完成 conversationId:{} runId:{} command:{} status:{}",
                    conversationId, runId, command, run.getStatus());
            return success(toRunResponse(run));
        } catch (RuntimeCommandUnknownException exception) {
            log.error("AgentRun命令 Runtime结果未知 runId:{}", runId, exception);
            return failure(ResponseCode.AGENT_RUNTIME_UNAVAILABLE);
        } catch (BusinessException exception) {
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("AgentRun命令系统异常 runId:{} command:{}", runId, command, exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private WorkspaceConversationEntity requireConversation(String conversationId, Long userId) {
        WorkspaceConversationEntity conversation = conversationService.require(conversationId);
        if (!conversation.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN);
        }
        return conversation;
    }

    private AgentRunCommandTypeVO parseCommand(String command) {
        return switch (command.toLowerCase()) {
            case "continue" -> AgentRunCommandTypeVO.CONTINUE_RUN;
            case "pause" -> AgentRunCommandTypeVO.PAUSE_RUN;
            case "resume" -> AgentRunCommandTypeVO.RESUME_RUN;
            case "steer" -> AgentRunCommandTypeVO.STEER_RUN;
            case "cancel" -> AgentRunCommandTypeVO.CANCEL_RUN;
            default -> throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        };
    }

    private WorkspaceConversationResponseDTO toConversationResponse(WorkspaceConversationEntity entity) {
        return WorkspaceConversationResponseDTO.builder().conversationId(entity.getConversationId())
                .workspaceId(entity.getWorkspaceId()).title(entity.getTitle()).build();
    }

    private AgentRunResponseDTO toRunResponse(AgentRunEntity entity) {
        return AgentRunResponseDTO.builder().runId(entity.getRunId()).conversationId(entity.getConversationId())
                .workspaceId(entity.getWorkspaceId()).status(entity.getStatus().name())
                .instruction(entity.getInstruction()).failureCode(entity.getFailureCode())
                .failureMessage(entity.getFailureMessage())
                .createdAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString())
                .finishedAt(entity.getFinishedAt() == null ? null : entity.getFinishedAt().toString())
                .build();
    }

    private AgentConversationMessageResponseDTO toMessageResponse(RuntimeConversationMessageVO message) {
        return AgentConversationMessageResponseDTO.builder()
                .id("transcript-" + message.sequence())
                .role(message.role())
                .content(message.content())
                .createdAt(message.createdAt())
                .build();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
