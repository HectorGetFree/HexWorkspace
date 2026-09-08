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

    private Long id;
    private String workspaceCode;
    private String workspaceName;
    /** 是否为注册时自动创建的默认资料库。 */
    private Boolean isDefault;
    private WorkspaceStatusVO status;
    private Long ownerUserId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isDefault() {
        return Boolean.TRUE.equals(isDefault);
    }

    public boolean isActive() {
        return WorkspaceStatusVO.ACTIVE == status;
    }
}
