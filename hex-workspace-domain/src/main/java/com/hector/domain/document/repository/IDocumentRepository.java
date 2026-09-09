package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档聚合仓储。
 */
public interface IDocumentRepository {

    /** 保存文档聚合，并回填数据库主键。 */
    DocumentEntity save(DocumentEntity documentEntity);

    /** 更新文档当前状态、名称、目录或版本指针。 */
    void update(DocumentEntity documentEntity);

    /** 按数据库主键查询文档。 */
    Optional<DocumentEntity> findById(Long documentId);

    /** 按对外业务编码查询文档。 */
    Optional<DocumentEntity> findByDocumentCode(String documentCode);

    /** 查询 Workspace 下的全部文档，由调用方决定是否过滤状态。 */
    List<DocumentEntity> listByWorkspaceId(Long workspaceId);

    /** 查询父目录下处于可用状态的直属文档。 */
    List<DocumentEntity> listActiveByParentId(Long workspaceId, Long parentId);

    /** 查询父目录下具有指定完整名称的可用文档，用于重名校验。 */
    Optional<DocumentEntity> findActiveByParentIdAndFileName(Long workspaceId, Long parentId, String fileName);
}
