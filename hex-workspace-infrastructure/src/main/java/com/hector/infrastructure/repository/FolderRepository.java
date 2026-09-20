package com.hector.infrastructure.repository;

import com.hector.domain.folder.model.entity.FolderEntity;
import com.hector.domain.folder.model.valobj.FolderStatusVO;
import com.hector.domain.folder.repository.IFolderRepository;
import com.hector.infrastructure.dao.IFolderDao;
import com.hector.infrastructure.dao.po.Folder;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 文件夹 MySQL 仓储实现，负责领域实体与持久化对象之间的转换。 */
@Repository
public class FolderRepository implements IFolderRepository {

    private static final Logger log = LoggerFactory.getLogger(FolderRepository.class);

    @Resource
    private IFolderDao folderDao;

    @Override
    public FolderEntity save(FolderEntity folderEntity) {
        log.info("文件夹仓储-保存文件夹开始 workspaceId:{} parentId:{} name:{}",
                folderEntity.getWorkspaceId(), folderEntity.getParentId(), folderEntity.getName());
        Folder folder = toPo(folderEntity);
        folderDao.insert(folder);
        FolderEntity savedFolder = toEntity(folder);
        log.info("文件夹仓储-保存文件夹完成 folderId:{} folderCode:{} workspaceId:{}",
                savedFolder.getId(), savedFolder.getFolderCode(), savedFolder.getWorkspaceId());
        return savedFolder;
    }

    @Override
    public void update(FolderEntity folderEntity) {
        log.info("文件夹仓储-更新文件夹开始 folderId:{} parentId:{} status:{}",
                folderEntity.getId(), folderEntity.getParentId(), folderEntity.getStatus());
        folderDao.update(toPo(folderEntity));
        log.info("文件夹仓储-更新文件夹完成 folderId:{}", folderEntity.getId());
    }

    @Override
    public Optional<FolderEntity> findById(Long folderId) {
        return Optional.ofNullable(folderDao.queryById(folderId)).map(this::toEntity);
    }

    @Override
    public Optional<FolderEntity> findRootByWorkspaceId(Long workspaceId) {
        return Optional.ofNullable(folderDao.queryRootByWorkspaceId(workspaceId)).map(this::toEntity);
    }

    @Override
    public List<FolderEntity> listAncestorChain(Long folderId) {
        return folderDao.queryAncestorChain(folderId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<FolderEntity> listByWorkspaceId(Long workspaceId) {
        return folderDao.queryByWorkspaceId(workspaceId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<FolderEntity> findActiveByParentIdAndName(Long workspaceId, Long parentId, String name) {
        return Optional.ofNullable(folderDao.queryActiveByParentIdAndName(workspaceId, parentId, name))
                .map(this::toEntity);
    }

    @Override
    public List<FolderEntity> listActiveByParentId(Long workspaceId, Long parentId) {
        return folderDao.queryActiveByParentId(workspaceId, parentId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private Folder toPo(FolderEntity folderEntity) {
        return Folder.builder()
                .id(folderEntity.getId())
                .folderCode(folderEntity.getFolderCode())
                .workspaceId(folderEntity.getWorkspaceId())
                .parentId(folderEntity.getParentId())
                .name(folderEntity.getName())
                .root(folderEntity.isRoot())
                .status(folderEntity.getStatus().getCode())
                .deletedAt(folderEntity.getDeletedAt())
                .createdAt(folderEntity.getCreatedAt())
                .updatedAt(folderEntity.getUpdatedAt())
                .build();
    }

    private FolderEntity toEntity(Folder folder) {
        return FolderEntity.builder()
                .id(folder.getId())
                .folderCode(folder.getFolderCode())
                .workspaceId(folder.getWorkspaceId())
                .parentId(folder.getParentId())
                .name(folder.getName())
                .root(folder.getRoot())
                .status(FolderStatusVO.fromCode(folder.getStatus()))
                .deletedAt(folder.getDeletedAt())
                .createdAt(folder.getCreatedAt())
                .updatedAt(folder.getUpdatedAt())
                .build();
    }
}
