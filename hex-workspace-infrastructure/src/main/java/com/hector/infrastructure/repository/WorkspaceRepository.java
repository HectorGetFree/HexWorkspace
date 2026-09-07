package com.hector.infrastructure.repository;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.model.valobj.WorkspaceTypeVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.infrastructure.dao.IWorkspaceDao;
import com.hector.infrastructure.dao.po.Workspace;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Workspace 的 MySQL 仓储实现。 */
@Repository
public class WorkspaceRepository implements IWorkspaceRepository {

    @Resource
    private IWorkspaceDao workspaceDao;

    @Override
    public WorkspaceEntity save(WorkspaceEntity workspaceEntity) {
        Workspace workspace = Workspace.builder()
                .workspaceCode(workspaceEntity.getWorkspaceCode())
                .workspaceName(workspaceEntity.getWorkspaceName())
                .workspaceType(workspaceEntity.getType().name())
                .status(workspaceEntity.getStatus().name())
                .ownerUserId(workspaceEntity.getOwnerUserId())
                .description(workspaceEntity.getDescription())
                .createdAt(workspaceEntity.getCreatedAt())
                .updatedAt(workspaceEntity.getUpdatedAt())
                .build();
        workspaceDao.insert(workspace);
        return toEntity(workspace);
    }

    @Override
    public Optional<WorkspaceEntity> findByWorkspaceCode(String workspaceCode) {
        return Optional.ofNullable(workspaceDao.queryWorkspaceByCode(workspaceCode)).map(this::toEntity);
    }

    @Override
    public Optional<WorkspaceEntity> findByOwnerUserIdAndType(Long ownerUserId, WorkspaceTypeVO type) {
        return Optional.ofNullable(workspaceDao.queryWorkspaceByOwnerUserIdAndType(ownerUserId, type.name()))
                .map(this::toEntity);
    }

    private WorkspaceEntity toEntity(Workspace workspace) {
        return WorkspaceEntity.builder()
                .id(workspace.getId())
                .workspaceCode(workspace.getWorkspaceCode())
                .workspaceName(workspace.getWorkspaceName())
                .type(WorkspaceTypeVO.valueOf(workspace.getWorkspaceType()))
                .status(WorkspaceStatusVO.valueOf(workspace.getStatus()))
                .ownerUserId(workspace.getOwnerUserId())
                .description(workspace.getDescription())
                .createdAt(workspace.getCreatedAt())
                .updatedAt(workspace.getUpdatedAt())
                .build();
    }
}
