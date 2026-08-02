package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
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
        log.info("文档仓储-保存文档开始 documentCode:{} tenantId:{} ownerUserId:{}",
                documentEntity.getDocumentCode(), documentEntity.getTenantId(), documentEntity.getOwnerUserId());
        Document document = toPo(documentEntity);
        documentDao.insert(document);
        DocumentEntity savedDocumentEntity = toEntity(document);
        log.info("文档仓储-保存文档完成 documentId:{} documentCode:{} tenantId:{}",
                savedDocumentEntity.getId(), savedDocumentEntity.getDocumentCode(), savedDocumentEntity.getTenantId());
        return savedDocumentEntity;
    }

    @Override
    public void update(DocumentEntity documentEntity) {
        log.info("文档仓储-更新文档开始 documentId:{} documentCode:{} status:{} currentVersion:{}",
                documentEntity.getId(), documentEntity.getDocumentCode(), documentEntity.getStatus(), documentEntity.getCurrentVersion());
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
    public List<DocumentEntity> listByTenantId(Long tenantId) {
        log.info("文档仓储-按租户查询列表开始 tenantId:{}", tenantId);
        List<DocumentEntity> documentEntities = documentDao.queryByTenantId(tenantId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("文档仓储-按租户查询列表完成 tenantId:{} documentCount:{}", tenantId, documentEntities.size());
        return documentEntities;
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
