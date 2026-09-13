package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.po.Document;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档聚合仓储实现。
 */
@Repository
public class DocumentRepository implements IDocumentRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentRepository.class);

    @Resource
    private IDocumentDao documentDao;

    @Override
    public DocumentEntity save(DocumentEntity documentEntity) {
        log.info("文档仓储-保存文档开始 documentCode:{} workspaceId:{} ownerUserId:{}",
                documentEntity.getDocumentCode(), documentEntity.getWorkspaceId(), documentEntity.getOwnerUserId());
        Document document = toPo(documentEntity);
        documentDao.insert(document);
        DocumentEntity savedDocumentEntity = toEntity(document);
        log.info("文档仓储-保存文档完成 documentId:{} documentCode:{} workspaceId:{}",
                savedDocumentEntity.getId(), savedDocumentEntity.getDocumentCode(), savedDocumentEntity.getWorkspaceId());
        return savedDocumentEntity;
    }

    @Override
    public void update(DocumentEntity documentEntity) {
        log.info("文档仓储-更新文档开始 documentId:{} documentCode:{} status:{}",
                documentEntity.getId(), documentEntity.getDocumentCode(), documentEntity.getStatus());
        documentDao.update(toPo(documentEntity));
        log.info("文档仓储-更新文档完成 documentId:{} documentCode:{}",
                documentEntity.getId(), documentEntity.getDocumentCode());
    }

    @Override
    public Optional<DocumentEntity> findById(Long documentId) {
        log.info("文档仓储-按ID查询开始 documentId:{}", documentId);
        Document document = documentDao.queryById(documentId);
        log.info("文档仓储-按ID查询完成 documentId:{} found:{}", documentId, document != null);
        return document == null ? Optional.empty() : Optional.of(toEntity(document));
    }

    @Override
    public Optional<DocumentEntity> findByDocumentCode(String documentCode) {
        log.info("文档仓储-按编码查询开始 documentCode:{}", documentCode);
        Document document = documentDao.queryByDocumentCode(documentCode);
        log.info("文档仓储-按编码查询完成 documentCode:{} found:{}", documentCode, document != null);
        return document == null ? Optional.empty() : Optional.of(toEntity(document));
    }

    @Override
    public Optional<DocumentEntity> findByUploadIdempotencyKey(Long workspaceId, Long ownerUserId, String idempotencyKey) {
        Document document = documentDao.queryByUploadIdempotencyKey(workspaceId, ownerUserId, idempotencyKey);
        return document == null ? Optional.empty() : Optional.of(toEntity(document));
    }

    @Override
    public List<DocumentEntity> listByWorkspaceId(Long workspaceId) {
        log.info("文档仓储-按Workspace查询列表开始 workspaceId:{}", workspaceId);
        List<DocumentEntity> documentEntities = documentDao.queryByWorkspaceId(workspaceId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("文档仓储-按Workspace查询列表完成 workspaceId:{} documentCount:{}", workspaceId, documentEntities.size());
        return documentEntities;
    }

    @Override
    public List<DocumentEntity> listActiveByParentId(Long workspaceId, Long parentId) {
        return documentDao.queryActiveByParentId(workspaceId, parentId).stream()
                .map(this::toEntity).collect(Collectors.toList());
    }

    @Override
    public Optional<DocumentEntity> findActiveByParentIdAndFileName(Long workspaceId, Long parentId, String fileName) {
        return Optional.ofNullable(documentDao.queryActiveByParentIdAndFileName(workspaceId, parentId, fileName))
                .map(this::toEntity);
    }

    private Document toPo(DocumentEntity documentEntity) {
        return Document.builder()
                .id(documentEntity.getId())
                .documentCode(documentEntity.getDocumentCode())
                .workspaceId(documentEntity.getWorkspaceId())
                .parentId(documentEntity.getParentId())
                .ownerUserId(documentEntity.getOwnerUserId())
                .title(documentEntity.getTitle())
                .fileName(documentEntity.getFileName())
                .documentType(documentEntity.getDocumentType().getCode())
                .status(documentEntity.getStatus().getCode())
                .storageType(documentEntity.getStorageType().getCode())
                .bucketName(documentEntity.getBucketName())
                .storagePath(documentEntity.getStoragePath())
                .contentType(documentEntity.getContentType())
                .contentSize(documentEntity.getContentSize())
                .contentHash(documentEntity.getContentHash())
                .idempotencyKey(documentEntity.getIdempotencyKey())
                .stagingPath(documentEntity.getStagingPath())
                .createdAt(documentEntity.getCreatedAt())
                .updatedAt(documentEntity.getUpdatedAt())
                .build();
    }

    private DocumentEntity toEntity(Document document) {
        return DocumentEntity.builder()
                .id(document.getId())
                .documentCode(document.getDocumentCode())
                .workspaceId(document.getWorkspaceId())
                .parentId(document.getParentId())
                .ownerUserId(document.getOwnerUserId())
                .title(document.getTitle())
                .fileName(document.getFileName())
                .documentType(DocumentTypeVO.fromCode(document.getDocumentType()))
                .status(DocumentStatusVO.fromCode(document.getStatus()))
                .storageType(DocumentStorageTypeVO.fromCode(document.getStorageType()))
                .bucketName(document.getBucketName())
                .storagePath(document.getStoragePath())
                .contentType(document.getContentType())
                .contentSize(document.getContentSize())
                .contentHash(document.getContentHash())
                .idempotencyKey(document.getIdempotencyKey())
                .stagingPath(document.getStagingPath())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
