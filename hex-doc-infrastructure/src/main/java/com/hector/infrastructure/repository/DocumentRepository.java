package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.infrastructure.dao.IDocumentDao;
import com.hector.infrastructure.dao.po.Document;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档聚合仓储实现。
 */
@Repository
public class DocumentRepository implements IDocumentRepository {

    @Resource
    private IDocumentDao documentDao;

    @Override
    public DocumentEntity save(DocumentEntity documentEntity) {
        Document document = toPo(documentEntity);
        documentDao.insert(document);
        return toEntity(document);
    }

    @Override
    public void update(DocumentEntity documentEntity) {
        documentDao.update(toPo(documentEntity));
    }

    @Override
    public Optional<DocumentEntity> findById(Long documentId) {
        Document document = documentDao.queryById(documentId);
        return document == null ? Optional.empty() : Optional.of(toEntity(document));
    }

    @Override
    public Optional<DocumentEntity> findByDocumentCode(String documentCode) {
        Document document = documentDao.queryByDocumentCode(documentCode);
        return document == null ? Optional.empty() : Optional.of(toEntity(document));
    }

    @Override
    public List<DocumentEntity> listByTenantId(Long tenantId) {
        return documentDao.queryByTenantId(tenantId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private Document toPo(DocumentEntity documentEntity) {
        return Document.builder()
                .id(documentEntity.getId())
                .documentCode(documentEntity.getDocumentCode())
                .tenantId(documentEntity.getTenantId())
                .ownerUserId(documentEntity.getOwnerUserId())
                .sourceFileId(documentEntity.getSourceFileId())
                .title(documentEntity.getTitle())
                .documentType(documentEntity.getDocumentType().name())
                .status(documentEntity.getStatus().name())
                .currentVersion(documentEntity.getCurrentVersion())
                .createdAt(documentEntity.getCreatedAt())
                .updatedAt(documentEntity.getUpdatedAt())
                .build();
    }

    private DocumentEntity toEntity(Document document) {
        return DocumentEntity.builder()
                .id(document.getId())
                .documentCode(document.getDocumentCode())
                .tenantId(document.getTenantId())
                .ownerUserId(document.getOwnerUserId())
                .sourceFileId(document.getSourceFileId())
                .title(document.getTitle())
                .documentType(DocumentTypeVO.valueOf(document.getDocumentType()))
                .status(DocumentStatusVO.valueOf(document.getStatus()))
                .currentVersion(document.getCurrentVersion())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .build();
    }
}
