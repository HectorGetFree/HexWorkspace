package com.hector.domain.workspace.repository;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import java.util.Optional;
import java.util.List;

/** Workspace 业务仓储。 */
public interface IWorkspaceRepository {

    WorkspaceEntity save(WorkspaceEntity workspaceEntity);

    Optional<WorkspaceEntity> findByWorkspaceCode(String workspaceCode);

    Optional<WorkspaceEntity> findDefaultByOwnerUserId(Long ownerUserId);

    List<WorkspaceEntity> listByOwnerUserId(Long ownerUserId);
}
