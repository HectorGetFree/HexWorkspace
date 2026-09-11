package com.hector.infrastructure.repository;

import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.model.valobj.WorkspaceStatusVO;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.infrastructure.dao.IWorkspaceDao;
import com.hector.infrastructure.dao.po.Workspace;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

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
                .isDefault(workspaceEntity.isDefault())
                .status(workspaceEntity.getStatus().getCode())
                .ownerUserId(workspaceEntity.getOwnerUserId())
                .description(workspaceEntity.getDescription())
                .createdAt(workspaceEntity.getCreatedAt())
                .updatedAt(workspaceEntity.getUpdatedAt())
                .build();
        workspaceDao.insert(workspace);
        return toEntity(workspace);
    }

    @Override
    public Optional<WorkspaceEntity> findById(Long workspaceId) {
        return Optional.ofNullable(workspaceDao.queryWorkspaceById(workspaceId)).map(this::toEntity);
    }

    @Override
    public Optional<WorkspaceEntity> findByWorkspaceCode(String workspaceCode) {
        return Optional.ofNullable(workspaceDao.queryWorkspaceByCode(workspaceCode)).map(this::toEntity);
    }

    @Override
    public Optional<WorkspaceEntity> findDefaultByOwnerUserId(Long ownerUserId) {
        return Optional.ofNullable(workspaceDao.queryDefaultWorkspaceByOwnerUserId(ownerUserId))
                .map(this::toEntity);
    }

    @Override
    public List<WorkspaceEntity> listByOwnerUserId(Long ownerUserId) {
        return workspaceDao.queryWorkspacesByOwnerUserId(ownerUserId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private WorkspaceEntity toEntity(Workspace workspace) {
        return WorkspaceEntity.builder()
                .id(workspace.getId())
                .workspaceCode(workspace.getWorkspaceCode())
                .workspaceName(workspace.getWorkspaceName())
                .isDefault(workspace.getIsDefault())
                .status(WorkspaceStatusVO.fromCode(workspace.getStatus()))
                .ownerUserId(workspace.getOwnerUserId())
                .description(workspace.getDescription())
                .createdAt(workspace.getCreatedAt())
                .updatedAt(workspace.getUpdatedAt())
                .build();
    }
}
