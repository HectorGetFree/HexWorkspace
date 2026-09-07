package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档版本仓储。
 */
public interface IDocumentVersionRepository {

    DocumentVersionEntity save(DocumentVersionEntity documentVersionEntity);

    void updateParseResult(DocumentVersionEntity documentVersionEntity);

    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNumber(Long documentId, Integer versionNumber);

    List<DocumentVersionEntity> listByDocumentId(Long documentId);
}
