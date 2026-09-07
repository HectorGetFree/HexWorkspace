package com.hector.domain.workspace.model.entity;

import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.model.valobj.WorkspaceTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Workspace 聚合根。
 * Workspace 是资料、成员、任务和 Agent 配置的一级隔离边界。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceEntity {

    private Long id;
    private String workspaceCode;
    private String workspaceName;
    private WorkspaceTypeVO type;
    private WorkspaceStatusVO status;
    private Long ownerUserId;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isPersonal() {
        return WorkspaceTypeVO.PERSONAL == type;
    }

    public boolean isActive() {
        return WorkspaceStatusVO.ACTIVE == status;
    }
}
