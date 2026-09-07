package com.hector.domain.workspace.repository;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceTypeVO;

import java.util.Optional;

/** Workspace 业务仓储。 */
public interface IWorkspaceRepository {

    WorkspaceEntity save(WorkspaceEntity workspaceEntity);

    Optional<WorkspaceEntity> findByWorkspaceCode(String workspaceCode);

    Optional<WorkspaceEntity> findByOwnerUserIdAndType(Long ownerUserId, WorkspaceTypeVO type);
}
