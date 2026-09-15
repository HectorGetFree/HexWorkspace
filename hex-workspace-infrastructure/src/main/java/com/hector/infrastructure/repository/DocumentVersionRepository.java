package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.po.DocumentVersion;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 文档不可变内容版本仓储实现。 */
@Repository
public class DocumentVersionRepository implements IDocumentVersionRepository {

    @Resource
    private IDocumentVersionDao documentVersionDao;

    @Override
    public DocumentVersionEntity save(DocumentVersionEntity version) {
        DocumentVersion documentVersion = toDocumentVersionPo(version);
        documentVersionDao.insert(documentVersion);
        return toDocumentVersionEntity(documentVersion);
    }

    @Override
    public Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(Long documentId, Long versionNo) {
        DocumentVersion documentVersion = documentVersionDao.queryByDocumentIdAndVersionNo(documentId, versionNo);
        return documentVersion == null ? Optional.empty() : Optional.of(toDocumentVersionEntity(documentVersion));
    }

    private DocumentVersion toDocumentVersionPo(DocumentVersionEntity entity) {
        return DocumentVersion.builder()
                .id(entity.getId())
                .documentId(entity.getDocumentId())
                .versionNo(entity.getVersionNo())
                .storageType(entity.getStorageType().getCode())
                .bucketName(entity.getBucketName())
                .storagePath(entity.getStoragePath())
                .contentType(entity.getContentType())
                .contentSize(entity.getContentSize())
                .contentHash(entity.getContentHash())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private DocumentVersionEntity toDocumentVersionEntity(DocumentVersion po) {
        return DocumentVersionEntity.builder()
                .id(po.getId())
                .documentId(po.getDocumentId())
                .versionNo(po.getVersionNo())
                .storageType(DocumentStorageTypeVO.fromCode(po.getStorageType()))
                .bucketName(po.getBucketName())
                .storagePath(po.getStoragePath())
                .contentType(po.getContentType())
                .contentSize(po.getContentSize())
                .contentHash(po.getContentHash())
                .createdBy(po.getCreatedBy())
                .createdAt(po.getCreatedAt())
                .build();
    }
}
