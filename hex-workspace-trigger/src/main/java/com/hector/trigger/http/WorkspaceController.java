package com.hector.trigger.http;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.service.IWorkspaceService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import com.hector.trigger.api.dto.WorkspaceListResponseDTO;
import com.hector.trigger.api.dto.WorkspaceResponseDTO;
import com.hector.trigger.api.response.Response;
import com.hector.trigger.http.security.SecurityUserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
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
     * 查询当前用户拥有的资料 Workspace。
     * 当前只列出 Owner 的 Workspace；资源级授权不会把他人的 Workspace 误呈现为用户自己的资料库。
     *
     * curl 示例：
     * curl -X GET "http://127.0.0.1:8098/api/v1/workspaces/my" \
     *   -H "Authorization: Bearer 替换为实际登录令牌"
     */
    @GetMapping("/my")
    public Response<WorkspaceListResponseDTO> listMyOwnedWorkspaces() {
        try {
            Long currentUserId = SecurityUserUtil.getCurrentUserId();
            // 查询边界限定为拥有者；成员可见空间将在 资源权限 迁移后另行合并。
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
                .isDefault(workspaceEntity.isDefault())
                .status(workspaceEntity.getStatus().name())
                .ownerUserId(workspaceEntity.getOwnerUserId())
                .description(workspaceEntity.getDescription())
                .build();
    }
}
