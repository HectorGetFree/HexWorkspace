package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentContentSnapshotEntity;
import com.hector.domain.document.model.valobj.DocumentContentSnapshotPurposeVO;
import com.hector.domain.document.model.valobj.DocumentContentSnapshotStatusVO;
import com.hector.domain.document.repository.IDocumentContentSnapshotRepository;
import com.hector.infrastructure.dao.IDocumentContentSnapshotDao;
import com.hector.infrastructure.dao.po.DocumentContentSnapshot;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 文档内容快照 MySQL 仓储实现。 */
@Repository
public class DocumentContentSnapshotRepository implements IDocumentContentSnapshotRepository {

    @Resource
    private IDocumentContentSnapshotDao snapshotDao;

    @Override
    public DocumentContentSnapshotEntity save(DocumentContentSnapshotEntity snapshot) {
        DocumentContentSnapshot persistenceObject = toPo(snapshot);
        snapshotDao.insert(persistenceObject);
        return toEntity(persistenceObject);
    }

    @Override
    public void update(DocumentContentSnapshotEntity snapshot) {
        snapshotDao.update(toPo(snapshot));
    }

    @Override
    public Optional<DocumentContentSnapshotEntity> findById(Long snapshotId) {
        return Optional.ofNullable(snapshotDao.queryById(snapshotId)).map(this::toEntity);
    }

    @Override
    public Optional<DocumentContentSnapshotEntity> findByContentCode(String snapshotCode) {
        return Optional.ofNullable(snapshotDao.queryByContentCode(snapshotCode)).map(this::toEntity);
    }

    @Override
    public List<DocumentContentSnapshotEntity> listByWorkspaceId(Long workspaceId) {
        return snapshotDao.queryByWorkspaceId(workspaceId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private DocumentContentSnapshot toPo(DocumentContentSnapshotEntity entity) {
        return DocumentContentSnapshot.builder()
                .id(entity.getId())
                .contentCode(entity.getContentCode())
                .workspaceId(entity.getWorkspaceId())
                .creatorUserId(entity.getCreatorUserId())
                .purpose(entity.getPurpose().name())
                .originName(entity.getOriginName())
                .suffix(entity.getSuffix())
                .contentType(entity.getContentType())
                .contentSize(entity.getContentSize())
                .storageType(entity.getStorageType())
                .bucketName(entity.getBucketName())
                .storagePath(entity.getStoragePath())
                .contentHash(entity.getContentHash())
                .status(entity.getStatus().name())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private DocumentContentSnapshotEntity toEntity(DocumentContentSnapshot po) {
        return DocumentContentSnapshotEntity.builder()
                .id(po.getId())
                .contentCode(po.getContentCode())
                .workspaceId(po.getWorkspaceId())
                .creatorUserId(po.getCreatorUserId())
                .purpose(DocumentContentSnapshotPurposeVO.valueOf(po.getPurpose()))
                .originName(po.getOriginName())
                .suffix(po.getSuffix())
                .contentType(po.getContentType())
                .contentSize(po.getContentSize())
                .storageType(po.getStorageType())
                .bucketName(po.getBucketName())
                .storagePath(po.getStoragePath())
                .contentHash(po.getContentHash())
                .status(DocumentContentSnapshotStatusVO.valueOf(po.getStatus()))
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
