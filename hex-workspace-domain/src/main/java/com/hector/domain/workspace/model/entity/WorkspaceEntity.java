package com.hector.domain.workspace.model.entity;

import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Workspace 聚合根。
 * Workspace 是 Owner 的资料、任务和 Agent 配置容器；协作权限由 Folder/Document 单独承载。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceEntity {

    /** Workspace 主键 ID。 */
    private Long id;

    /** Workspace 唯一业务编码。 */
    private String workspaceCode;

    /** Workspace 展示名称。 */
    private String workspaceName;

    /** 是否为注册时自动创建的默认资料库。 */
    private Boolean isDefault;

    /** Workspace 当前生命周期状态。 */
    private WorkspaceStatusVO status;

    /** Workspace 所有者用户 ID。 */
    private Long ownerUserId;

    /** Workspace 的补充描述。 */
    private String description;

    /** Workspace 创建时间。 */
    private LocalDateTime createdAt;

    /** Workspace 最后更新时间。 */
    private LocalDateTime updatedAt;

    public boolean isDefault() {
        return Boolean.TRUE.equals(isDefault);
    }

    public boolean isActive() {
        return WorkspaceStatusVO.ACTIVE == status;
    }
}
