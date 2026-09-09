package com.hector.domain.document.repository;

import com.hector.domain.document.model.entity.DocumentContentEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文档内容快照仓储接口。
 */
public interface IDocumentContentRepository {

    /**
     * 保存内容快照。
     */
    DocumentContentEntity save(DocumentContentEntity contentEntity);

    /**
     * 更新内容快照。
     */
    void update(DocumentContentEntity contentEntity);

    /**
     * 按主键查询内容快照。
     */
    Optional<DocumentContentEntity> findById(Long contentId);

    /**
     * 按业务编码查询内容快照。
     */
    Optional<DocumentContentEntity> findByContentCode(String contentCode);

    /**
     * 按 Workspace ID 查询内容快照列表。
     */
    List<DocumentContentEntity> listByWorkspaceId(Long workspaceId);
}
