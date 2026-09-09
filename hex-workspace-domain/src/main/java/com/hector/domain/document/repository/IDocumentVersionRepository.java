package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档版本仓储。
 */
public interface IDocumentVersionRepository {

    /** 保存不可变文档版本，并回填数据库主键。 */
    DocumentVersionEntity save(DocumentVersionEntity documentVersionEntity);

    /** 更新异步解析状态及解析产物引用。 */
    void updateParseResult(DocumentVersionEntity documentVersionEntity);

    /** 按文档 ID 和版本号查询唯一版本。 */
    Optional<DocumentVersionEntity> findByDocumentIdAndVersionNumber(Long documentId, Integer versionNumber);

    /** 按版本号顺序查询文档的全部历史版本。 */
    List<DocumentVersionEntity> listByDocumentId(Long documentId);
}
