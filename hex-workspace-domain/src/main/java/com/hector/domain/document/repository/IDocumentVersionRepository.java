package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.Optional;

/** 文档不可变内容版本仓储。 */
public interface IDocumentVersionRepository {

    /** 保存一份新内容版本并回填主键。 */
    DocumentVersionEntity save(DocumentVersionEntity version);

    /** 按文档与版本号查询不可变内容快照。 */
    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNo(Long documentId, Long versionNo);
}
