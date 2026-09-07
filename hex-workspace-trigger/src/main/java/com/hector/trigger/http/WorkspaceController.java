package com.hector.trigger.http;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.WorkspaceCreateRequestDTO;
import com.hector.trigger.api.dto.WorkspaceListResponseDTO;
import com.hector.trigger.api.dto.WorkspaceResponseDTO;
import com.hector.trigger.api.request.Request;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/** Workspace 接口控制器。 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    @Resource
    private IWorkspaceService workspaceService;

    /**
     * 创建团队 Workspace。个人 Workspace 只能由注册流程自动创建。
     *
     * curl 示例：
     * curl -X POST "http://127.0.0.1:8098/api/v1/workspaces" \
     *   -H "Content-Type: application/json" \
     *   -H "Authorization: Bearer 替换为实际登录令牌" \
     *   -d '{"data":{"workspaceName":"产品团队","description":"产品资料与协作空间"}}'
     */
    @PostMapping
    public Response<WorkspaceResponseDTO> createTeamWorkspace(
            @RequestBody Request<WorkspaceCreateRequestDTO> request) {
        try {
            // 1. 从认证上下文取得拥有者，禁止客户端伪造 ownerUserId。
            WorkspaceCreateRequestDTO data = requireData(request);
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            log.info("创建团队Workspace开始 workspaceName:{} ownerUserId:{}", data.getWorkspaceName(), currentUserId);

            // 2. 仅调用 TEAM 创建用例；个人空间不允许通过通用创建接口伪造。
            WorkspaceEntity workspaceEntity = workspaceService.createTeamWorkspace(
                    data.getWorkspaceName(), currentUserId, data.getDescription());

            // 3. 返回脱敏后的资源标识和状态，不暴露领域实体本身。
            return success(toResponse(workspaceEntity));
        } catch (BusinessException exception) {
            log.error("创建团队Workspace业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("创建团队Workspace系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    /**
     * 查询当前用户拥有的个人和团队 Workspace。
     * 当前不包含成员身份加入的团队空间，待 Membership 迁移后扩展。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/my" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/my")
    public Response<WorkspaceListResponseDTO> listMyOwnedWorkspaces() {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            // 查询边界限定为拥有者；成员可见空间将在 WorkspaceMembership 迁移后另行合并。
            List<WorkspaceResponseDTO> workspaces = workspaceService.listOwnedWorkspaces(currentUserId).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
            return success(WorkspaceListResponseDTO.builder().workspaces(workspaces).build());
        } catch (BusinessException exception) {
            log.error("查询我的Workspace业务异常 code:{} info:{}", exception.getCode(), exception.getInfo(), exception);
            return failure(exception.getCode(), exception.getInfo());
        } catch (Exception exception) {
            log.error("查询我的Workspace系统异常", exception);
            return failure(ResponseCode.SYSTEM_ERROR);
        }
    }

    private WorkspaceResponseDTO toResponse(WorkspaceEntity workspaceEntity) {
        return WorkspaceResponseDTO.builder()
                .workspaceId(workspaceEntity.getId())
                .workspaceCode(workspaceEntity.getWorkspaceCode())
                .workspaceName(workspaceEntity.getWorkspaceName())
                .type(workspaceEntity.getType().name())
                .status(workspaceEntity.getStatus().name())
                .ownerUserId(workspaceEntity.getOwnerUserId())
                .description(workspaceEntity.getDescription())
                .build();
    }
}
