package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentContentEntity;
import com.hector.domain.document.model.valobj.DocumentContentPurposeVO;
import com.hector.domain.document.model.valobj.DocumentContentStatusVO;
import com.hector.domain.document.repository.IDocumentContentRepository;
import com.hector.infrastructure.dao.IDocumentContentDao;
import com.hector.infrastructure.dao.po.DocumentContent;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** 文档内容快照 MySQL 仓储实现。 */
@Repository
public class DocumentContentRepository implements IDocumentContentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentContentRepository.class);

    @Resource
    private IDocumentContentDao contentDao;

    @Override
    public DocumentContentEntity save(DocumentContentEntity contentEntity) {
        log.info("文档内容仓储-保存内容快照开始 contentCode:{} workspaceId:{}",
                contentEntity.getContentCode(), contentEntity.getWorkspaceId());
        DocumentContent content = toPo(contentEntity);
        contentDao.insert(content);
        DocumentContentEntity savedContent = toEntity(content);
        log.info("文档内容仓储-保存内容快照完成 contentId:{} contentCode:{}",
                savedContent.getId(), savedContent.getContentCode());
        return savedContent;
    }

    @Override
    public void update(DocumentContentEntity contentEntity) {
        log.info("文档内容仓储-更新内容快照开始 contentId:{} status:{}",
                contentEntity.getId(), contentEntity.getStatus());
        contentDao.update(toPo(contentEntity));
        log.info("文档内容仓储-更新内容快照完成 contentId:{}", contentEntity.getId());
    }

    @Override
    public Optional<DocumentContentEntity> findById(Long contentId) {
        return Optional.ofNullable(contentDao.queryById(contentId)).map(this::toEntity);
    }

    @Override
    public Optional<DocumentContentEntity> findByContentCode(String contentCode) {
        return Optional.ofNullable(contentDao.queryByContentCode(contentCode)).map(this::toEntity);
    }

    @Override
    public List<DocumentContentEntity> listByWorkspaceId(Long workspaceId) {
        return contentDao.queryByWorkspaceId(workspaceId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private DocumentContent toPo(DocumentContentEntity entity) {
        return DocumentContent.builder()
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

    private DocumentContentEntity toEntity(DocumentContent content) {
        return DocumentContentEntity.builder()
                .id(content.getId())
                .contentCode(content.getContentCode())
                .workspaceId(content.getWorkspaceId())
                .creatorUserId(content.getCreatorUserId())
                .purpose(DocumentContentPurposeVO.valueOf(content.getPurpose()))
                .originName(content.getOriginName())
                .suffix(content.getSuffix())
                .contentType(content.getContentType())
                .contentSize(content.getContentSize())
                .storageType(content.getStorageType())
                .bucketName(content.getBucketName())
                .storagePath(content.getStoragePath())
                .contentHash(content.getContentHash())
                .status(DocumentContentStatusVO.valueOf(content.getStatus()))
                .createdAt(content.getCreatedAt())
                .updatedAt(content.getUpdatedAt())
                .build();
    }
}
