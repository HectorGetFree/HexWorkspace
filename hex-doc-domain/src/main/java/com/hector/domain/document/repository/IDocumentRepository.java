package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档聚合仓储。
 */
public interface IDocumentRepository {

    DocumentEntity save(DocumentEntity documentEntity);

    void update(DocumentEntity documentEntity);

    Optional<DocumentEntity> findById(Long documentId);

    Optional<DocumentEntity> findByDocumentCode(String documentCode);

    List<DocumentEntity> listByTenantId(Long tenantId);
}
